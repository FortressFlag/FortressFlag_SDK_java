package com.fortressflag.server;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.regex.Pattern;

/**
 * The transport: one GET, treated as talking to a potentially hostile network. The three
 * rules every FortressFlag SDK transport carries (ports of the sibling SDKs' rules):
 *
 * <ul>
 *   <li>BOTH {@code connectTimeout} and the per-request timeout are set — connect alone
 *       lets an accepted-then-silent server hang the poller thread forever (trap #6).
 *   <li>Redirects are refused ({@code Redirect.NEVER} — the default client FOLLOWS them,
 *       and a followed redirect could replay the Authorization header, which carries a
 *       genuine secret, to wherever it points). A 3xx surfaces as an unexpected status.
 *   <li>The response body is capped at 1 MiB, read one byte past the cap — a hostile or
 *       broken server must not balloon this process's memory. Real payloads are
 *       kilobytes.
 * </ul>
 */
class Transport {

    /** Bounds a response body. Shared with the cache's load bound. */
    static final int MAX_RESPONSE_BYTES = 1 << 20;

    enum FetchKind {
        SUCCESS,
        NOT_MODIFIED, // 304 — a success: the cached ruleset is current
        UNAUTHORIZED, // 401/403 — revoked or wrong key; keep serving
        RATE_LIMITED, // 429
        SERVER_ERROR, // 5xx
        UNEXPECTED_STATUS, // incl. a refused redirect's 3xx
        TRANSPORT_ERROR, // connect/timeout — the network itself failed
        RESPONSE_TOO_LARGE
    }

    record FetchOutcome(FetchKind kind, byte[] raw, String etag, int status, int retryAfterSeconds) {
        static FetchOutcome of(FetchKind kind) {
            return new FetchOutcome(kind, new byte[0], "", 0, 0);
        }
    }

    private final HttpClient client;
    private final URI uri;
    private final String key;
    private final java.time.Duration timeout;

    Transport(Configuration configuration) {
        this.client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(configuration.httpTimeout())
                .build();
        this.uri = URI.create(configuration.baseUrl()
                + "/v1/server/ruleset?sv=" + Envelope.SUPPORTED_SERVER_CONTRACT_VERSION);
        this.key = configuration.key();
        this.timeout = configuration.httpTimeout();
    }

    /**
     * One conditional GET of the ruleset export — exactly the request the contract shows,
     * no more: Authorization, Accept, If-None-Match. There is no SDK-version header and no
     * telemetry; an undocumented header would be an additive contract change that goes
     * through an ADR (ADR-0016).
     */
    FetchOutcome fetchRuleset(String etag) {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .GET()
                .timeout(timeout)
                .header("Authorization", "Bearer " + key)
                .header("Accept", "application/json");
        if (!etag.isEmpty()) {
            request.header("If-None-Match", etag);
        }
        HttpResponse<InputStream> response;
        try {
            response = client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            // Refused connections, DNS failures, timeouts — the snapshot keeps serving.
            return FetchOutcome.of(FetchKind.TRANSPORT_ERROR);
        }

        try (InputStream body = response.body()) {
            int status = response.statusCode();
            if (status == 304) {
                return FetchOutcome.of(FetchKind.NOT_MODIFIED);
            }
            if (status == 200) {
                // One byte past the cap distinguishes "exactly at the cap" from "over it".
                byte[] raw = body.readNBytes(MAX_RESPONSE_BYTES + 1);
                if (raw.length > MAX_RESPONSE_BYTES) {
                    return FetchOutcome.of(FetchKind.RESPONSE_TOO_LARGE);
                }
                String responseEtag = response.headers().firstValue("ETag").orElse("");
                return new FetchOutcome(FetchKind.SUCCESS, raw, responseEtag, 0, 0);
            }
            if (status == 401 || status == 403) {
                return FetchOutcome.of(FetchKind.UNAUTHORIZED);
            }
            if (status == 429) {
                int retryAfter = parseRetryAfterSeconds(
                        response.headers().firstValue("Retry-After").orElse(""));
                return new FetchOutcome(FetchKind.RATE_LIMITED, new byte[0], "", 0, retryAfter);
            }
            if (status >= 500) {
                return new FetchOutcome(FetchKind.SERVER_ERROR, new byte[0], "", status, 0);
            }
            return new FetchOutcome(FetchKind.UNEXPECTED_STATUS, new byte[0], "", status, 0);
        } catch (IOException exception) {
            return FetchOutcome.of(FetchKind.TRANSPORT_ERROR);
        }
    }

    private static final Pattern DELTA_SECONDS = Pattern.compile("[0-9]+");

    /**
     * Reads Retry-After as delta-seconds ONLY. The HTTP-date form is deliberately not
     * parsed (the sibling SDKs' rule): date parsing against a wrong local clock can
     * produce an enormous delay, and the backoff caps whatever this returns anyway.
     */
    static int parseRetryAfterSeconds(String header) {
        if (header.isEmpty() || !DELTA_SECONDS.matcher(header).matches()) {
            return 0;
        }
        try {
            return Integer.parseInt(header);
        } catch (NumberFormatException exception) {
            return 0; // more digits than an int — the backoff cap makes the value moot
        }
    }
}
