package com.fortressflag.server;

/**
 * How long to wait before the next poll — a port of the sibling SDKs' backoff, reasoning
 * included, because the second job matters MORE at server scale. The obvious job is to
 * stop a process hammering a failing backend. The less obvious job is DE-SYNCHRONISATION:
 * without jitter, every process in a fleet that started polling at the same moment —
 * which, after an outage, is all of them — retries in lockstep, and the backend that just
 * came back up is knocked over by its own clients. Jitter on the success path matters as
 * much as on the failure path: a 100-process deployment restarted by an orchestrator polls
 * as 100 spikes a minute forever unless the routine interval is jittered too.
 *
 * <p>Randomness is injected so the bounds can be asserted in tests instead of hoped for.
 */
final class Backoff {
    private Backoff() {}

    @FunctionalInterface
    interface RandomInRange {
        double next(double low, double high);
    }

    /** The ceiling: half an hour. A process that has been failing for hours is almost
     * certainly firewalled or misconfigured, and there is nothing to gain from asking more
     * often — the snapshot is already answering every call. */
    private static final double CAP_SECONDS = 1800.0;
    /** The delay after the first failure. Doubles from here. */
    private static final double BASE_SECONDS = 2.0;
    /** Spreads every delay ±20%. */
    private static final double JITTER_FRACTION = 0.2;

    /** The wait in millis after consecutiveFailures failures in a row. */
    static long retryDelayMillis(int consecutiveFailures, RandomInRange random) {
        if (consecutiveFailures <= 0) {
            return 0;
        }
        // Exponent capped before the power so a long-offline process cannot overflow the
        // multiplier on its ten-thousandth failed attempt.
        int exponent = Math.min(consecutiveFailures - 1, 32);
        double raw = Math.min(BASE_SECONDS * Math.pow(2, exponent), CAP_SECONDS);
        return jitteredMillis(raw, random);
    }

    /** The wait in millis before the next routine poll — jittered, see the class comment. */
    static long pollDelayMillis(java.time.Duration interval, RandomInRange random) {
        return jitteredMillis(interval.toMillis() / 1000.0, random);
    }

    /** Obeys a server that told us when to come back — but never past the cap: a hostile
     * or misconfigured Retry-After of a year must not silently disable flag updates for a
     * process until it restarts. */
    static long retryDelayWithServerHintMillis(
            int retryAfterSeconds, int consecutiveFailures, RandomInRange random) {
        if (retryAfterSeconds <= 0) {
            return retryDelayMillis(consecutiveFailures, random);
        }
        return (long) (Math.min(retryAfterSeconds, CAP_SECONDS) * 1000.0);
    }

    private static long jitteredMillis(double seconds, RandomInRange random) {
        double spread = seconds * JITTER_FRACTION;
        return (long) (random.next(seconds - spread, seconds + spread) * 1000.0);
    }
}
