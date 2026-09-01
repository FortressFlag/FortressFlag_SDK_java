package com.fortressflag.server;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Places one context in [0, 100) for one flag's percentage rollout (ADR-0008, published in
 * contract-v2.md and pinned by vectors/buckets.json):
 *
 * <pre>uint64_be(SHA-256(contextKey + ":" + flagKey)[0..8]) mod 100</pre>
 *
 * <p>The contextKey is EXACTLY the opaque identifier string the caller gave this SDK —
 * never validated, trimmed or normalised: the backend hashes a device ID the same way, and
 * any deviation from "hash exactly the UTF-8 bytes given" flips cohorts between
 * components. The top eight digest bytes are an UNSIGNED 64-bit integer:
 * {@link Long#remainderUnsigned} — a signed {@code %} answers a NEGATIVE bucket for half
 * of all contexts, which matches every rollout (recorded trap #4; the bucket vectors catch
 * it on the first run). Nothing is stored; a bucket is recomputed per evaluation. The
 * ":" + flagKey suffix makes buckets per-flag and sticky.
 */
final class Bucket {
    private Bucket() {}

    static int of(String contextKey, String flagKey) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            // SHA-256 is mandatory in every conforming JRE; this cannot happen, and the
            // fail-safe answer if it somehow did is a bucket that matches nothing.
            return 100;
        }
        byte[] sum = digest.digest(
                (contextKey + ":" + flagKey).getBytes(StandardCharsets.UTF_8));
        long top = 0;
        for (int i = 0; i < 8; i++) {
            top = (top << 8) | (sum[i] & 0xFF);
        }
        return (int) Long.remainderUnsigned(top, 100);
    }
}
