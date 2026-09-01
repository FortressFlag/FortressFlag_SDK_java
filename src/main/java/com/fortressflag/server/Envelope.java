package com.fortressflag.server;

import com.fortressflag.server.Ruleset.FlagConfig;
import com.fortressflag.server.json.JsonParseException;
import com.fortressflag.server.json.JsonParser;
import com.fortressflag.server.json.JsonValue;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The wire envelope (server-contract-v1.md): the client envelope design, reused. {@code
 * sig} is omitted until backend M4 ships; when present it is a detached signature over the
 * payload's exact base64url bytes.
 */
final class Envelope {
    private Envelope() {}

    /** The sv this SDK requests and accepts. */
    static final int SUPPORTED_SERVER_CONTRACT_VERSION = 1;

    private static final Pattern BASE64URL = Pattern.compile("[A-Za-z0-9_-]+");

    /** RFC 3339 with fractional seconds tolerated — the shape is gated BEFORE
     * Instant.parse so a lenient library parse cannot verify what the siblings reject. */
    private static final Pattern RFC3339 = Pattern.compile(
            "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?(Z|[+-]\\d{2}:\\d{2})");

    /**
     * Decodes UNPADDED base64url strictly, or returns null. {@code Base64.getUrlDecoder()}
     * tolerates padding and mis-length input in ways that differ by position (recorded
     * trap #5): the alphabet is validated and {@code =} refused before decoding, so a
     * payload the sibling SDKs reject cannot verify here.
     */
    static byte[] decodeBase64Url(String s) {
        if (s.isEmpty() || !BASE64URL.matcher(s).matches()) {
            return null;
        }
        try {
            return Base64.getUrlDecoder().decode(s);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    record Wire(String payload, String sig, boolean sigPresent) {}

    record Parsed(Wire envelope, byte[] payloadBytes) {}

    /** The decoded payload document. */
    record Payload(
            int sv,
            String tenant,
            String project,
            String environment,
            String issuedAt,
            String expiresAt,
            Map<String, FlagConfig> flags) {}

    /**
     * Splits raw bytes into the envelope and its decoded payload bytes, or null — a body
     * that is not an envelope is a rejection, never a crash (captive portals serve HTML
     * with a 200; the transport treats the server as hostile). The in-repo parser's
     * exceptions stop here.
     */
    static Parsed parseEnvelope(byte[] raw) {
        JsonValue parsed;
        try {
            parsed = JsonParser.parse(raw);
        } catch (JsonParseException exception) {
            return null;
        }
        if (!(parsed instanceof JsonValue.JsonObject object)) {
            return null;
        }
        Map<String, JsonValue> members = object.members();
        if (!(members.get("payload") instanceof JsonValue.JsonString payload)
                || payload.value().isEmpty()) {
            return null;
        }
        JsonValue sigRaw = members.get("sig");
        String sig = null;
        boolean sigPresent = false;
        if (sigRaw != null && !(sigRaw instanceof JsonValue.JsonNull)) {
            if (!(sigRaw instanceof JsonValue.JsonString sigString)) {
                return null;
            }
            sig = sigString.value();
            sigPresent = true;
        }
        byte[] payloadBytes = decodeBase64Url(payload.value());
        if (payloadBytes == null) {
            return null;
        }
        return new Parsed(new Wire(payload.value(), sig, sigPresent), payloadBytes);
    }

    /**
     * Decodes the payload document, requiring the fields whose absence would make the
     * binding checks meaningless. Unknown fields are ignored — additive server changes
     * ride sv=1 — and the raw bytes are what the cache retains.
     *
     * <p>Flag KINDS are validated here, and a payload carrying one this build does not
     * know is rejected WHOLE — the client SDKs' union-violation posture: the contract
     * makes a new kind an sv bump, so an unknown kind on sv=1 is corruption or hostility,
     * and under wholesale snapshot overwrite an accepted half-broken payload would
     * dislodge held values into fallbacks. (Value-vs-kind mismatches inside rules stay
     * per-flag fail-closed in the evaluator.)
     */
    static Payload parsePayload(byte[] payloadBytes) {
        JsonValue parsed;
        try {
            parsed = JsonParser.parse(payloadBytes);
        } catch (JsonParseException exception) {
            return null;
        }
        if (!(parsed instanceof JsonValue.JsonObject object)) {
            return null;
        }
        Map<String, JsonValue> members = object.members();
        if (!(members.get("sv") instanceof JsonValue.JsonNumber sv)
                || sv.value() != Math.floor(sv.value())) {
            return null;
        }
        if (!(members.get("environment") instanceof JsonValue.JsonString environment)
                || environment.value().isEmpty()) {
            return null;
        }
        if (!(members.get("issuedAt") instanceof JsonValue.JsonString issuedAt)
                || issuedAt.value().isEmpty()) {
            return null;
        }
        if (!(members.get("expiresAt") instanceof JsonValue.JsonString expiresAt)
                || expiresAt.value().isEmpty()) {
            return null;
        }
        JsonValue flagsRaw = members.get("flags");
        Map<String, FlagConfig> flags;
        if (flagsRaw == null) {
            flags = Map.of();
        } else {
            flags = Ruleset.parseFlags(flagsRaw);
            if (flags == null) {
                return null;
            }
        }
        for (FlagConfig config : flags.values()) {
            switch (config.kind()) {
                case "boolean", "string", "number" -> {}
                default -> {
                    return null;
                }
            }
        }
        String tenant = members.get("tenant") instanceof JsonValue.JsonString t ? t.value() : "";
        String project = members.get("project") instanceof JsonValue.JsonString p ? p.value() : "";
        return new Payload(
                (int) sv.value(),
                tenant,
                project,
                environment.value(),
                issuedAt.value(),
                expiresAt.value(),
                flags);
    }

    /** Parses the contract's RFC 3339 timestamps to an Instant, or null. */
    static Instant parseWireTime(String s) {
        if (!RFC3339.matcher(s).matches()) {
            return null;
        }
        try {
            return java.time.OffsetDateTime.parse(s).toInstant();
        } catch (DateTimeParseException exception) {
            return null;
        }
    }
}
