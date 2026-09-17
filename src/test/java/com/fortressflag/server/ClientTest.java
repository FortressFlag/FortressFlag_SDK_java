package com.fortressflag.server;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fortressflag.server.Transport.FetchKind;
import com.fortressflag.server.Transport.FetchOutcome;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ClientTest {

    private static final Context CTX = new Context("user-1", Map.of("cohort", "beta"));

    /** Plays outcomes in order, then repeats the last forever. */
    static final class ScriptedTransport extends Transport {
        private final List<FetchOutcome> outcomes;
        int calls;

        ScriptedTransport(List<FetchOutcome> outcomes) {
            super(Configuration.builder(Fixtures.KEY).signature(SignaturePolicy.disabled()).build());
            this.outcomes = new ArrayList<>(outcomes);
        }

        @Override
        FetchOutcome fetchRuleset(String etag) {
            FetchOutcome outcome = outcomes.get(Math.min(calls, outcomes.size() - 1));
            calls++;
            return outcome;
        }
    }

    static FetchOutcome goodFetch() {
        return new FetchOutcome(
                FetchKind.SUCCESS, Fixtures.envelope(Fixtures.payload()), "\"e1\"", 0, 0);
    }

    static Client client(Transport transport, String cachePath) {
        // Fixtures are unsigned: the default policy is required-with-production (ADR-0025).
        Configuration.Builder builder = Configuration.builder(Fixtures.KEY)
                .signature(SignaturePolicy.disabled());
        if (cachePath != null) {
            builder.cachePath(cachePath);
        }
        return new Client(builder.build(), transport, () -> Fixtures.NOW, (low, high) -> (low + high) / 2);
    }

    @Test
    void startReadyAndStartAgainIsAPureStatusRead() {
        ScriptedTransport transport = new ScriptedTransport(List.of(goodFetch()));
        try (Client client = client(transport, null)) {
            assertEquals(StartOutcome.READY, client.start(Duration.ofSeconds(5)));
            assertEquals(StartOutcome.READY, client.start(Duration.ofMillis(50)));
        }
    }

    @Test
    void timedOutServingDefaultsAndFallbacksServe() {
        ScriptedTransport transport =
                new ScriptedTransport(List.of(FetchOutcome.of(FetchKind.TRANSPORT_ERROR)));
        try (Client client = client(transport, null)) {
            assertEquals(StartOutcome.TIMED_OUT, client.start(Duration.ofSeconds(5)));
            assertTrue(client.boolValue("dark-mode", CTX, true));
        }
    }

    @Test
    void cacheOnlyWhenTheFileAnswersAndTheNetworkDoesNot(@TempDir Path directory) throws IOException {
        Path path = directory.resolve("cache.json");
        var payload = Fixtures.payload();
        payload.put("issuedAt", "2026-08-21T07:00:00Z");
        payload.put("expiresAt", "2026-08-21T07:30:00Z"); // expiry long past — the asymmetry
        Files.write(path, Fixtures.envelope(payload));
        ScriptedTransport transport =
                new ScriptedTransport(List.of(FetchOutcome.of(FetchKind.TRANSPORT_ERROR)));
        try (Client client = client(transport, path.toString())) {
            assertEquals(StartOutcome.CACHE_ONLY, client.start(Duration.ofSeconds(5)));
            assertTrue(client.boolValue("dark-mode", CTX, false));
            assertEquals("cache", client.diagnostics().snapshotSource());
        }
    }

    @Test
    void aRejectedEnvelopeNeverDislodgesAnything() {
        var hostile = Fixtures.payload();
        hostile.put("environment", "prod");
        ScriptedTransport transport = new ScriptedTransport(List.of(
                goodFetch(),
                new FetchOutcome(FetchKind.SUCCESS, Fixtures.envelope(hostile), "\"e2\"", 0, 0)));
        try (Client client = client(transport, null)) {
            assertEquals(StartOutcome.READY, client.start(Duration.ofSeconds(5)));
            client.record(transport.fetchRuleset("\"e1\""));
            assertTrue(client.boolValue("dark-mode", CTX, false)); // held value survives
            Diagnostics diagnostics = client.diagnostics();
            assertEquals("rejectedEnvelope", diagnostics.lastFetchStatus());
            assertEquals("environmentMismatch", diagnostics.lastRejection());
            assertEquals("\"e1\"", diagnostics.etag()); // the rejected etag NOT adopted
        }
    }

    @Test
    void aRevokedKeyKeepsServing() {
        ScriptedTransport transport = new ScriptedTransport(
                List.of(goodFetch(), FetchOutcome.of(FetchKind.UNAUTHORIZED)));
        try (Client client = client(transport, null)) {
            client.start(Duration.ofSeconds(5));
            client.record(transport.fetchRuleset(""));
            client.record(transport.fetchRuleset(""));
            assertTrue(client.boolValue("dark-mode", CTX, false));
            Diagnostics diagnostics = client.diagnostics();
            assertEquals("unauthorized", diagnostics.lastFetchStatus());
            assertEquals(2, diagnostics.consecutiveFailures());
        }
    }

    @Test
    void wholesaleOverwriteDropsDepartedFlags() {
        var second = Fixtures.payload();
        second.put("flags", "{\"checkout-cta\":{\"kind\":\"string\",\"default\":\"buy-now\",\"rules\":[]}}");
        ScriptedTransport transport = new ScriptedTransport(List.of(
                goodFetch(),
                new FetchOutcome(FetchKind.SUCCESS, Fixtures.envelope(second), "\"e2\"", 0, 0)));
        try (Client client = client(transport, null)) {
            client.start(Duration.ofSeconds(5));
            assertTrue(client.boolValue("dark-mode", CTX, false));
            client.record(transport.fetchRuleset("\"e1\""));
            assertFalse(client.boolValue("dark-mode", CTX, false)); // archived → fallback
            assertEquals(1, client.diagnostics().resolutions().fallbackUnknownFlag());
        }
    }

    @Test
    void kindMismatchAnswersTheFallbackNeverAThrow() {
        ScriptedTransport transport = new ScriptedTransport(List.of(goodFetch()));
        try (Client client = client(transport, null)) {
            client.start(Duration.ofSeconds(5));
            assertTrue(client.boolValue("checkout-cta", CTX, true));
            assertEquals("x", client.stringValue("dark-mode", CTX, "x"));
            assertEquals(7.0, client.numberValue("dark-mode", CTX, 7.0));
            assertEquals(3, client.diagnostics().resolutions().fallbackKindMismatch());
        }
    }

    @Test
    void diagnosticsCarriesNoKeyInAnyForm() {
        ScriptedTransport transport = new ScriptedTransport(List.of(goodFetch()));
        try (Client client = client(transport, null)) {
            client.start(Duration.ofSeconds(5));
            assertFalse(client.diagnostics().toString().contains("ffs_"));
        }
    }

    @Test
    void cacheWriteIsVerbatimOwnerOnlyAndSurvivesARestart(@TempDir Path directory) throws IOException {
        Path path = directory.resolve("cache.json");
        FetchOutcome first = goodFetch();
        try (Client client = client(new ScriptedTransport(List.of(first)), path.toString())) {
            client.start(Duration.ofSeconds(5));
            assertEquals("stored", client.diagnostics().cacheState());
            assertArrayEquals(first.raw(), Files.readAllBytes(path));
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(path);
            assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), permissions);
        }

        try (Client reborn = client(
                new ScriptedTransport(List.of(FetchOutcome.of(FetchKind.TRANSPORT_ERROR))),
                path.toString())) {
            assertEquals(StartOutcome.CACHE_ONLY, reborn.start(Duration.ofSeconds(5)));
            assertTrue(reborn.boolValue("dark-mode", CTX, false));
        }
    }

    @Test
    void aCorruptCacheDegradesToLoadFailed(@TempDir Path directory) throws IOException {
        Path path = directory.resolve("cache.json");
        Files.write(path, "not-an-envelope".getBytes());
        try (Client client = client(
                new ScriptedTransport(List.of(FetchOutcome.of(FetchKind.TRANSPORT_ERROR))),
                path.toString())) {
            assertEquals(StartOutcome.TIMED_OUT, client.start(Duration.ofSeconds(5)));
            assertEquals("loadFailed", client.diagnostics().cacheState());
        }
    }
}
