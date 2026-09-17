package com.fortressflag.server;

import java.util.Map;

/**
 * The Ed25519 public keys FortressFlag signs production rulesets with (backend ADR-0025).
 * Raw 32-byte keys by key ID. Rotation is a publish, not a release: key N+1 is added here
 * and shipped before the backend starts signing with it; N is retired one release later.
 * The same keys are published on the docs page {@code concepts/payload-signing} and in
 * ADR-0025 — nowhere else, and there is no discovery endpoint by design.
 */
public final class TrustedKeys {
    private TrustedKeys() {}

    /** The current production key ID. Grammar {@code [a-z0-9-]+}, never a colon. */
    public static final String PRODUCTION_KEY_ID = "prod-2026-09-k1";

    // The raw key as hex, ONE line so it can be checked against the base64url form by eye:
    // EaEF8MHNu3onHxemTg3-OcrKrq7ODsZIVEp-IVV2ojg  (ADR-0025, minted 2026-09-16)
    private static final String PRODUCTION_KEY_HEX =
            "11a105f0c1cdbb7a271f17a64e0dfe39cacaaeaece0ec648544a7e215576a238";

    /** A fresh map of the production keys; callers may not mutate the SDK's copy. */
    public static Map<String, byte[]> fortressFlagProduction() {
        return Map.of(PRODUCTION_KEY_ID, decodeHex(PRODUCTION_KEY_HEX));
    }

    static byte[] decodeHex(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }
}
