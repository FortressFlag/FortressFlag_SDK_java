package com.fortressflag.server;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class BackoffTest {

    /** The injected-randomness seam: assert the BOUNDS handed to random, never a sample. */
    private static final class Capture implements Backoff.RandomInRange {
        final List<double[]> calls = new ArrayList<>();

        @Override
        public double next(double low, double high) {
            calls.add(new double[] {low, high});
            return (low + high) / 2;
        }
    }

    @Test
    void retryDoublesFromTwoSecondsAndJittersTwentyPercent() {
        Capture random = new Capture();
        assertEquals(2_000, Backoff.retryDelayMillis(1, random));
        assertEquals(4_000, Backoff.retryDelayMillis(2, random));
        assertEquals(8_000, Backoff.retryDelayMillis(3, random));
        assertEquals(1.6, random.calls.get(0)[0]);
        assertEquals(2.4, random.calls.get(0)[1]);
    }

    @Test
    void retryCapsAt1800sAndTheExponentIsCappedBeforeThePower() {
        Capture random = new Capture();
        assertEquals(1_800_000, Backoff.retryDelayMillis(11, random));
        assertEquals(1_800_000, Backoff.retryDelayMillis(10_000, random)); // no overflow
    }

    @Test
    void zeroFailuresWaitsZero() {
        assertEquals(0, Backoff.retryDelayMillis(0, new Capture()));
    }

    @Test
    void pollDelayJittersTheSuccessPathToo() {
        Capture random = new Capture();
        assertEquals(60_000, Backoff.pollDelayMillis(Duration.ofSeconds(60), random));
        assertEquals(48.0, random.calls.get(0)[0]); // ±20% — the de-synchronisation job
        assertEquals(72.0, random.calls.get(0)[1]);
    }

    @Test
    void serverHintWinsButIsCapped() {
        Capture random = new Capture();
        assertEquals(17_000, Backoff.retryDelayWithServerHintMillis(17, 5, random));
        assertEquals(1_800_000, Backoff.retryDelayWithServerHintMillis(31_536_000, 1, random));
        assertEquals(2_000, Backoff.retryDelayWithServerHintMillis(0, 1, random));
    }
}
