package com.fortressflag.server;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/** Shared fixtures: fixed clock, payload/envelope builders. The key is short and
 * low-entropy on purpose (CLAUDE.md §4); the clock is fixed so expiry tests assert
 * instants, not races. */
final class Fixtures {
    private Fixtures() {}

    static final Instant NOW = Instant.parse("2026-08-21T10:15:00Z");
    static final String KEY = "ffs_dev_k";

    static Map<String, Object> payload() {
        Map<String, Object> base = new LinkedHashMap<>();
        base.put("sv", 1);
        base.put("tenant", "t");
        base.put("project", "default");
        base.put("environment", "dev");
        base.put("issuedAt", "2026-08-21T10:00:00Z");
        base.put("expiresAt", "2026-08-21T10:30:00Z");
        base.put(
                "flags",
                "{\"dark-mode\":{\"kind\":\"boolean\",\"default\":false,\"rules\":"
                        + "[{\"conditions\":[{\"tagKey\":\"cohort\",\"operator\":\"eq\","
                        + "\"value\":\"beta\"}],\"serve\":true}]},"
                        + "\"checkout-cta\":{\"kind\":\"string\",\"default\":\"buy-now\",\"rules\":[]}}");
        return base;
    }

    /** Hand-serialised so tests control the exact bytes; values are pre-encoded JSON. */
    static String payloadJson(Map<String, Object> payload) {
        StringBuilder builder = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : payload.entrySet()) {
            if (!first) {
                builder.append(',');
            }
            first = false;
            builder.append('"').append(entry.getKey()).append("\":");
            Object value = entry.getValue();
            if (value instanceof String s && (s.startsWith("{") || s.startsWith("["))) {
                builder.append(s);
            } else if (value instanceof String s) {
                builder.append('"').append(s).append('"');
            } else {
                builder.append(value);
            }
        }
        return builder.append('}').toString();
    }

    static byte[] envelope(Map<String, Object> payload) {
        return envelope(payload, null);
    }

    static byte[] envelope(Map<String, Object> payload, String sig) {
        String encoded = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(payloadJson(payload).getBytes(StandardCharsets.UTF_8));
        String body = sig == null
                ? "{\"payload\":\"" + encoded + "\"}"
                : "{\"payload\":\"" + encoded + "\",\"sig\":\"" + sig + "\"}";
        return body.getBytes(StandardCharsets.UTF_8);
    }
}
