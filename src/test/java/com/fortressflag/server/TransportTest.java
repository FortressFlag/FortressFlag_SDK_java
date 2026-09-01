package com.fortressflag.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fortressflag.server.Transport.FetchKind;
import com.fortressflag.server.Transport.FetchOutcome;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Transport tests against a REAL local HTTP server (stdlib com.sun.net.httpserver — fine
 * in tests) — the header-allowlist assertion is the mechanical enforcement of "the
 * contract names every header". */
final class TransportTest {

    private HttpServer server;
    private final AtomicReference<List<String>> seenHeaders = new AtomicReference<>(List.of());

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private interface Responder {
        void respond(com.sun.net.httpserver.HttpExchange exchange) throws IOException;
    }

    private Transport serve(Responder responder) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            seenHeaders.set(exchange.getRequestHeaders().keySet().stream().toList());
            responder.respond(exchange);
            exchange.close();
        });
        server.start();
        return transportFor("http://127.0.0.1:" + server.getAddress().getPort(), 2);
    }

    private static Transport transportFor(String baseUrl, int timeoutSeconds) {
        return new Transport(Configuration.builder("ffs_dev_k12345")
                .baseUrl(baseUrl)
                .httpTimeout(Duration.ofSeconds(timeoutSeconds))
                .build());
    }

    @Test
    void sendsExactlyTheContractsHeaders() throws IOException {
        Transport transport = serve(exchange -> {
            exchange.getResponseHeaders().set("ETag", "\"e1\"");
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        FetchOutcome outcome = transport.fetchRuleset("\"old\"");
        assertEquals(FetchKind.SUCCESS, outcome.kind());
        // Transport-mechanical headers (Host/Connection/Content-Length/User-Agent and
        // HttpClient's Upgrade negotiation) are the platform's; everything else must be
        // the contract's three and nothing more.
        Set<String> allowed = Set.of(
                "authorization", "accept", "if-none-match",
                "host", "connection", "user-agent", "accept-encoding",
                "upgrade", "http2-settings", "content-length");
        for (String header : seenHeaders.get()) {
            assertTrue(allowed.contains(header.toLowerCase(Locale.ROOT)), "unexpected header: " + header);
        }
    }

    @Test
    void theUrlCarriesSv1AndTheEtagRoundTrips() throws IOException {
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> inm = new AtomicReference<>();
        Transport transport = serve(exchange -> {
            path.set(exchange.getRequestURI().toString());
            inm.set(exchange.getRequestHeaders().getFirst("If-None-Match"));
            exchange.getResponseHeaders().set("ETag", "\"e2\"");
            exchange.sendResponseHeaders(200, 2);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write("{}".getBytes(StandardCharsets.UTF_8));
            }
        });
        FetchOutcome outcome = transport.fetchRuleset("\"e1\"");
        assertEquals("/v1/server/ruleset?sv=1", path.get());
        assertEquals("\"e1\"", inm.get());
        assertEquals("\"e2\"", outcome.etag());
    }

    @Test
    void statusMapping() throws IOException {
        record Case(int status, String retryAfter, String location, FetchKind expected) {}
        List<Case> cases = List.of(
                new Case(304, null, null, FetchKind.NOT_MODIFIED),
                new Case(401, null, null, FetchKind.UNAUTHORIZED),
                new Case(403, null, null, FetchKind.UNAUTHORIZED),
                new Case(429, "17", null, FetchKind.RATE_LIMITED),
                new Case(503, null, null, FetchKind.SERVER_ERROR),
                new Case(302, null, "http://evil.example/", FetchKind.UNEXPECTED_STATUS),
                new Case(418, null, null, FetchKind.UNEXPECTED_STATUS));
        for (Case c : cases) {
            Transport transport = serve(exchange -> {
                if (c.retryAfter() != null) {
                    exchange.getResponseHeaders().set("Retry-After", c.retryAfter());
                }
                if (c.location() != null) {
                    exchange.getResponseHeaders().set("Location", c.location());
                }
                exchange.sendResponseHeaders(c.status(), -1);
            });
            FetchOutcome outcome = transport.fetchRuleset("");
            assertEquals(c.expected(), outcome.kind(), String.valueOf(c.status()));
            if (outcome.kind() == FetchKind.RATE_LIMITED) {
                assertEquals(17, outcome.retryAfterSeconds());
            }
            server.stop(0);
        }
    }

    @Test
    void aBodyOverTheCapIsRefusedAndAtTheCapAccepted() throws IOException {
        int[] size = {Transport.MAX_RESPONSE_BYTES + 1};
        Transport transport = serve(exchange -> {
            exchange.sendResponseHeaders(200, size[0]);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(new byte[size[0]]);
            }
        });
        assertEquals(FetchKind.RESPONSE_TOO_LARGE, transport.fetchRuleset("").kind());
        size[0] = Transport.MAX_RESPONSE_BYTES;
        assertEquals(FetchKind.SUCCESS, transport.fetchRuleset("").kind());
    }

    @Test
    void aHungServerHitsTheTimeout() throws IOException {
        Transport transport = serve(exchange -> {
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        // 2s request timeout against a 5s hang.
        assertEquals(FetchKind.TRANSPORT_ERROR, transport.fetchRuleset("").kind());
    }

    @Test
    void aRefusedConnectionIsATransportError() {
        Transport transport = transportFor("http://127.0.0.1:1", 2);
        assertEquals(FetchKind.TRANSPORT_ERROR, transport.fetchRuleset("").kind());
    }

    @Test
    void retryAfterIsDeltaSecondsOnly() {
        assertEquals(17, Transport.parseRetryAfterSeconds("17"));
        assertEquals(0, Transport.parseRetryAfterSeconds(""));
        assertEquals(0, Transport.parseRetryAfterSeconds("-1"));
        assertEquals(0, Transport.parseRetryAfterSeconds("Wed, 21 Oct 2026 07:28:00 GMT"));
    }
}
