package com.fortressflag.server;

/**
 * The entry point. {@code create} is the ONE place the SDK throws (a malformed key, before
 * anything serves — {@link MalformedKeyException} via {@link Configuration.Builder#build}).
 * After that: {@link Client#start} never throws, getters never throw, {@link Client#close}
 * is idempotent, and every failure resolves to the caller's fallback with the reason on
 * {@link Client#diagnostics}.
 */
public final class FortressFlag {
    private FortressFlag() {}

    public static Client create(Configuration configuration) {
        return new Client(configuration, new Transport(configuration));
    }
}
