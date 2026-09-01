package com.fortressflag.server.json;

/**
 * A parse rejection — INTERNAL to the SDK: the verifier catches it and maps it to the
 * {@code malformedPayload}/{@code malformedEnvelope} rejection codes; nothing propagates
 * to the caller (Founding §8.1). The message carries a byte offset and what was expected,
 * never the input bytes — the payload is customer data.
 */
public final class JsonParseException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    JsonParseException(String message, int offset) {
        super(message + " at offset " + offset);
    }
}
