package com.fortressflag.server;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fortressflag.server.Transport.FetchKind;
import com.fortressflag.server.Transport.FetchOutcome;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The suite that proves the one thing this SDK actually promises: flagging can fail in any
 * way at all, and the customer's JVM neither crashes nor sees an exception. Every other
 * test checks that a specific thing works; these check that nothing breaks when everything
 * is wrong at once. Ported from the Go SDK's chaos suite, with the concurrent variant done
 * with REAL threads hammering getters during hostile swaps — the JVM's actual race
 * surface.
 */
final class ChaosTest {

    private static final Context CTX = new Context("user-1", Map.of("cohort", "beta"));

    private static FetchOutcome success(byte[] raw) {
        return new FetchOutcome(FetchKind.SUCCESS, raw, "\"hostile\"", 0, 0);
    }

    private static List<FetchOutcome> hostileOutcomes() {
        List<FetchOutcome> outcomes = new ArrayList<>();
        // Bodies that are not envelopes.
        outcomes.add(success(new byte[0]));
        outcomes.add(success("<html>please log in</html>".getBytes(StandardCharsets.UTF_8)));
        outcomes.add(success("{".getBytes(StandardCharsets.UTF_8)));
        outcomes.add(success("null".getBytes(StandardCharsets.UTF_8)));
        outcomes.add(success(new byte[4096]));
        // Structurally valid but untrustworthy envelopes.
        var wrongEnv = Fixtures.payload();
        wrongEnv.put("environment", "prod");
        outcomes.add(success(Fixtures.envelope(wrongEnv)));
        var badSv = Fixtures.payload();
        badSv.put("sv", 99);
        outcomes.add(success(Fixtures.envelope(badSv)));
        var expired = Fixtures.payload();
        expired.put("issuedAt", "2026-08-21T08:00:00Z");
        expired.put("expiresAt", "2026-08-21T08:30:00Z"); // the replay window
        outcomes.add(success(Fixtures.envelope(expired)));
        var future = Fixtures.payload();
        future.put("issuedAt", "2026-08-21T12:00:00Z");
        outcomes.add(success(Fixtures.envelope(future)));
        outcomes.add(success(Arrays.copyOf(Fixtures.envelope(Fixtures.payload()), 20)));
        var badRules = Fixtures.payload();
        badRules.put("flags", "{\"x\":{\"kind\":\"boolean\",\"rules\":\"not-a-list\"}}");
        outcomes.add(success(Fixtures.envelope(badRules)));
        var badFlags = Fixtures.payload();
        badFlags.put("flags", "\"not-a-map\"");
        outcomes.add(success(Fixtures.envelope(badFlags)));
        var unknownKind = Fixtures.payload();
        unknownKind.put("flags", "{\"x\":{\"kind\":\"datetime\",\"rules\":[]}}");
        outcomes.add(success(Fixtures.envelope(unknownKind))); // whole-payload rejection
        // Transport-level.
        outcomes.add(FetchOutcome.of(FetchKind.TRANSPORT_ERROR));
        outcomes.add(FetchOutcome.of(FetchKind.UNAUTHORIZED));
        outcomes.add(new FetchOutcome(FetchKind.RATE_LIMITED, new byte[0], "", 0, 0));
        outcomes.add(new FetchOutcome(FetchKind.RATE_LIMITED, new byte[0], "", 0, 31_536_000));
        outcomes.add(new FetchOutcome(FetchKind.SERVER_ERROR, new byte[0], "", 500, 0));
        outcomes.add(new FetchOutcome(FetchKind.SERVER_ERROR, new byte[0], "", 503, 0));
        outcomes.add(new FetchOutcome(FetchKind.UNEXPECTED_STATUS, new byte[0], "", 418, 0));
        outcomes.add(new FetchOutcome(FetchKind.UNEXPECTED_STATUS, new byte[0], "", 400, 0));
        outcomes.add(FetchOutcome.of(FetchKind.RESPONSE_TOO_LARGE));
        outcomes.add(FetchOutcome.of(FetchKind.NOT_MODIFIED)); // with nothing behind it
        return outcomes;
    }

    /** A transport whose next outcome the test chooses per call. */
    static final class PushTransport extends Transport {
        volatile FetchOutcome next = FetchOutcome.of(FetchKind.TRANSPORT_ERROR);

        PushTransport() {
            super(Configuration.builder(Fixtures.KEY).build());
        }

        @Override
        FetchOutcome fetchRuleset(String etag) {
            return next;
        }
    }

    private static Client client(Transport transport, String cachePath) {
        Configuration.Builder builder = Configuration.builder(Fixtures.KEY);
        if (cachePath != null) {
            builder.cachePath(cachePath);
        }
        return new Client(builder.build(), transport, () -> Fixtures.NOW, (low, high) -> (low + high) / 2);
    }

    @Test
    void aClientHoldingAGoodValueNeverLosesIt(@TempDir Path directory) throws IOException {
        Path path = directory.resolve("cache.json");
        PushTransport transport = new PushTransport();
        transport.next = ClientTest.goodFetch();
        try (Client client = client(transport, path.toString())) {
            assertEquals(StartOutcome.READY, client.start(Duration.ofSeconds(5)));
            byte[] cachedBefore = Files.readAllBytes(path);

            for (FetchOutcome outcome : hostileOutcomes()) {
                transport.next = outcome;
                client.record(outcome);
                assertTrue(client.boolValue("dark-mode", CTX, false));
                assertEquals("buy-now", client.stringValue("checkout-cta", CTX, "fallback"));
            }
            assertArrayEquals(cachedBefore, Files.readAllBytes(path),
                    "hostile outcomes reached the cache file");
        }
    }

    @Test
    void aColdClientAnswersFallbacksForever() {
        PushTransport transport = new PushTransport();
        try (Client client = client(transport, null)) {
            for (FetchOutcome outcome : hostileOutcomes()) {
                client.record(outcome);
                assertFalse(client.boolValue("dark-mode", CTX, false));
                assertEquals(7.0, client.numberValue("retry-limit", CTX, 7.0));
            }
        }
    }

    @Test
    void underARequiredPolicyEverySignatureShapeRejects() {
        PushTransport transport = new PushTransport();
        Configuration configuration = Configuration.builder(Fixtures.KEY)
                .signature(SignaturePolicy.required(Map.of("k1", new byte[32])))
                .build();
        try (Client client = new Client(configuration, transport, () -> Fixtures.NOW,
                (low, high) -> (low + high) / 2)) {
            for (String sig : new String[] {
                "", "garbage", "ed25519:AAAA", "p256:k1:AAAA", "ed25519:unknown:AAAA", "ed25519:k1:AAAA"
            }) {
                client.record(new FetchOutcome(
                        FetchKind.SUCCESS, Fixtures.envelope(Fixtures.payload(), sig), "\"s\"", 0, 0));
                Diagnostics diagnostics = client.diagnostics();
                assertEquals("rejectedEnvelope", diagnostics.lastFetchStatus(), sig);
                assertEquals(0, diagnostics.flagCount(), sig);
            }
        }
    }

    @Test
    void concurrentGettersDuringHostileSwaps() throws InterruptedException {
        PushTransport transport = new PushTransport();
        transport.next = ClientTest.goodFetch();
        Client client = client(transport, null);
        client.start(Duration.ofSeconds(5));

        AtomicBoolean stop = new AtomicBoolean(false);
        List<String> failures = java.util.Collections.synchronizedList(new ArrayList<>());
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            Thread thread = new Thread(() -> {
                while (!stop.get()) {
                    try {
                        if (!client.boolValue("dark-mode", CTX, false)) {
                            failures.add("held value lost");
                            return;
                        }
                        client.diagnostics();
                    } catch (RuntimeException exception) {
                        failures.add("threw: " + exception);
                        return;
                    }
                }
            });
            thread.setDaemon(true);
            thread.start();
            threads.add(thread);
        }
        try {
            List<FetchOutcome> hostile = hostileOutcomes();
            for (int cycle = 0; cycle < 50; cycle++) {
                for (FetchOutcome outcome : hostile) {
                    client.record(outcome);
                }
                client.record(ClientTest.goodFetch());
            }
        } finally {
            stop.set(true);
            for (Thread thread : threads) {
                thread.join(5_000);
            }
            client.close();
        }
        assertEquals(List.of(), failures);
    }

    @Test
    void aPoisonedCacheCannotSmuggleASnapshot(@TempDir Path directory) throws IOException {
        Path path = directory.resolve("cache.json");
        var hostile = Fixtures.payload();
        hostile.put("environment", "prod");
        Files.write(path, Fixtures.envelope(hostile));
        try (Client client = client(new PushTransport(), path.toString())) {
            assertEquals(StartOutcome.TIMED_OUT, client.start(Duration.ofSeconds(5)));
            assertEquals("loadFailed", client.diagnostics().cacheState());
        }
    }
}
