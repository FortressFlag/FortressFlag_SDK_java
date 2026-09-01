package com.fortressflag.server;

import java.util.HashMap;
import java.util.Map;

/**
 * How the SDK treats the envelope's signature. Use {@link #disabled()} — the default, and
 * the only workable policy until the backend's signing milestone (M4) ships — or
 * {@link #required(Map)}, which rejects every envelope whose signature cannot be verified
 * against the trusted keys — INCLUDING, until M4 ships an algorithm, every envelope there
 * is: the verification stub can reject a forgery but can never accept one. Rejection is
 * never fatal — the SDK keeps serving its last verified snapshot.
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

    boolean knowsKeyId(String keyId) {
        return trustedKeys.containsKey(keyId);
    }
}
