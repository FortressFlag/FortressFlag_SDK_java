package com.fortressflag.server;

import java.util.HashMap;
import java.util.Map;

/**
 * How the SDK treats the envelope's signature (backend ADR-0025). The default is
 * {@link #fortressFlagProduction()}: every envelope must carry a signature that verifies
 * against a key in {@link TrustedKeys}, and anything else is rejected — fail closed.
 * {@link #disabled()} is the explicit, greppable opt-out for local development against a
 * backend that has no signing key configured; {@link #required(Map)} pins a different key
 * set (for example the staging key). Rejection is never fatal — the SDK keeps serving its
 * last verified snapshot.
 */
public final class SignaturePolicy {

    private static final SignaturePolicy DISABLED = new SignaturePolicy(false, Map.of());

    private final boolean required;
    private final Map<String, byte[]> trustedKeys;

    private SignaturePolicy(boolean required, Map<String, byte[]> trustedKeys) {
        this.required = required;
        this.trustedKeys = trustedKeys;
    }

    /** A named, greppable value rather than a silent fallback. */
    public static SignaturePolicy disabled() {
        return DISABLED;
    }

    /** The default: required, trusting exactly the production keys in {@link TrustedKeys}. */
    public static SignaturePolicy fortressFlagProduction() {
        return required(TrustedKeys.fortressFlagProduction());
    }

    /** The map and each key's bytes are copied. */
    public static SignaturePolicy required(Map<String, byte[]> trustedKeys) {
        Map<String, byte[]> copied = new HashMap<>();
        for (Map.Entry<String, byte[]> entry : trustedKeys.entrySet()) {
            copied.put(entry.getKey(), entry.getValue().clone());
        }
        return new SignaturePolicy(true, Map.copyOf(copied));
    }

    boolean isRequired() {
        return required;
    }

    /** The raw key bytes for a key ID, or null when the ID is not trusted. */
    byte[] trustedKey(String keyId) {
        return trustedKeys.get(keyId);
    }
}
