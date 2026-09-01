package com.fortressflag.server;

import java.time.Instant;

/**
 * The one-line answer to "why are flags not updating?". Contains no secrets — the key
 * appears in no form, not even its prefix.
 */
public record Diagnostics(
        Instant lastFetchAt,
        String lastFetchStatus,
        String lastRejection,
        String etag,
        int consecutiveFailures,
        Instant snapshotIssuedAt,
        String snapshotSource,
        int flagCount,
        String cacheState,
        Resolutions resolutions) {

    public record Resolutions(
            long served,
            long fallbackNoSnapshot,
            long fallbackUnknownFlag,
            long fallbackKindMismatch,
            long fallbackNoValue) {}
}
