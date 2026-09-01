package com.fortressflag.server;

import com.fortressflag.server.Ruleset.Decoded;
import com.fortressflag.server.Ruleset.FlagConfig;
import com.fortressflag.server.Transport.FetchKind;
import com.fortressflag.server.Transport.FetchOutcome;
import com.fortressflag.server.Verifier.Expectations;
import com.fortressflag.server.Verifier.Result;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

/**
 * The client core: one daemon scheduler thread feeding a volatile snapshot the getters
 * read lock-free. After {@link FortressFlag#create}, nothing in here throws to the caller
 * or exits the JVM — every failure becomes backoff plus a diagnostics note while the last
 * snapshot keeps answering (Founding §8.1/§8.4).
 *
 * <p>The concurrency model (CLAUDE.md §8): the snapshot is a {@code volatile} reference
 * publishing an immutable record; getters read it ONCE into a local and evaluate against
 * that, never twice. Counters are {@link LongAdder}; mutable diagnostic state sits behind
 * one lock the evaluation path never takes. Do not add a lock to the read path "for
 * safety" — the lock-free read IS the design (Go's atomic.Pointer, translated).
 */
public final class Client implements AutoCloseable {

    private record Snapshot(Map<String, FlagConfig> flags, Instant issuedAt, boolean fromCache) {}

    private final Configuration configuration;
    private final Transport fetcher;
    private final EnvelopeCache cache;

    private volatile Snapshot snapshot;

    private final Object stateLock = new Object();
    private Instant lastFetchAt;
    private String lastFetchStatus = "neverFetched";
    private Verifier.RejectionCode lastRejection;
    private String etag = "";
    private int consecutiveFailures;
    private String cacheState;

    private final LongAdder served = new LongAdder();
    private final LongAdder fallbackNoSnapshot = new LongAdder();
    private final LongAdder fallbackUnknownFlag = new LongAdder();
    private final LongAdder fallbackKindMismatch = new LongAdder();
    private final LongAdder fallbackNoValue = new LongAdder();

    private final CountDownLatch firstAttempt = new CountDownLatch(1);
    private final CountDownLatch networkReady = new CountDownLatch(1);

    private final Object startLock = new Object();
    private boolean started;
    private volatile boolean closed;

    /** A DAEMON thread: a flag SDK must never block JVM exit (CLAUDE.md §2 — the flag is
     * load-bearing, not tidiness). */
    private static final ThreadFactory DAEMON_FACTORY = runnable -> {
        Thread thread = new Thread(runnable, "fortressflag-poller");
        thread.setDaemon(true);
        return thread;
    };

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(DAEMON_FACTORY);

    /** Injected for tests; production uses the real clock and ThreadLocalRandom. */
    private final Supplier<Instant> now;
    private final Backoff.RandomInRange random;

    Client(Configuration configuration, Transport fetcher) {
        this(configuration, fetcher, Instant::now,
                (low, high) -> low >= high ? low : ThreadLocalRandom.current().nextDouble(low, high));
    }

    Client(Configuration configuration, Transport fetcher, Supplier<Instant> now,
            Backoff.RandomInRange random) {
        this.configuration = configuration;
        this.fetcher = fetcher;
        this.cache = configuration.cachePath().isEmpty()
                ? null
                : new EnvelopeCache(configuration.cachePath());
        this.cacheState = this.cache == null ? "disabled" : "empty";
        this.now = now;
        this.random = random;
    }

    /**
     * Loads the cache if configured, launches the poller, and blocks until the first fetch
     * attempt completes or the timeout elapses. Never throws: the worst outcome is
     * "serving fallbacks until the network appears", stated in the StartOutcome. Calling
     * start again reports the current state without side effects.
     */
    public StartOutcome start(Duration timeout) {
        synchronized (startLock) {
            if (!started) {
                started = true;
                loadCache();
                scheduler.execute(this::pollOnce);
            }
        }
        try {
            if (!firstAttempt.await(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                // The deadline elapsed; the poller keeps running — start's timeout is a
                // start deadline only (the Go Start-ctx rule).
                return statusAfterWait();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        return statusAfterWait();
    }

    private StartOutcome statusAfterWait() {
        if (networkReady.getCount() == 0) {
            return StartOutcome.READY;
        }
        Snapshot current = snapshot;
        if (current != null && current.fromCache()) {
            return StartOutcome.CACHE_ONLY;
        }
        return networkReady.getCount() == 0 ? StartOutcome.READY : StartOutcome.TIMED_OUT;
    }

    /** Stops the poller. Idempotent, and safe before start. */
    @Override
    public void close() {
        closed = true;
        scheduler.shutdownNow();
    }

    public boolean boolValue(String flagKey, Context context, boolean fallback) {
        Decoded decoded = value(flagKey, context, "boolean");
        return decoded.ok() && decoded.value() instanceof Boolean b ? b : fallback;
    }

    public String stringValue(String flagKey, Context context, String fallback) {
        Decoded decoded = value(flagKey, context, "string");
        return decoded.ok() && decoded.value() instanceof String s ? s : fallback;
    }

    public double numberValue(String flagKey, Context context, double fallback) {
        Decoded decoded = value(flagKey, context, "number");
        return decoded.ok() && decoded.value() instanceof Double d ? d : fallback;
    }

    public Diagnostics diagnostics() {
        Snapshot current = snapshot;
        Diagnostics.Resolutions resolutions = new Diagnostics.Resolutions(
                served.sum(),
                fallbackNoSnapshot.sum(),
                fallbackUnknownFlag.sum(),
                fallbackKindMismatch.sum(),
                fallbackNoValue.sum());
        synchronized (stateLock) {
            return new Diagnostics(
                    lastFetchAt,
                    lastFetchStatus,
                    lastRejection == null ? "" : lastRejection.wire,
                    etag,
                    consecutiveFailures,
                    current == null ? null : current.issuedAt(),
                    current == null ? "" : current.fromCache() ? "cache" : "network",
                    current == null ? 0 : current.flags().size(),
                    cacheState,
                    resolutions);
        }
    }

    // -- internals -------------------------------------------------------------------

    private void loadCache() {
        if (cache == null) {
            return;
        }
        byte[] raw = cache.load();
        if (raw == null) {
            return;
        }
        // Expiry deliberately unenforced: THE cache-load half of the contract's expiry
        // asymmetry — a service that restarts after a long outage keeps evaluating with
        // what it last saw. See Verifier.Expectations.
        Result result = Verifier.verify(raw, configuration.signature(),
                new Expectations(configuration.environment(), now.get(), false));
        synchronized (stateLock) {
            if (result.envelope() == null) {
                cacheState = "loadFailed";
                return;
            }
            cacheState = "loaded";
        }
        snapshot = snapshotOf(result.envelope(), true);
    }

    private static Snapshot snapshotOf(Verifier.VerifiedEnvelope envelope, boolean fromCache) {
        return new Snapshot(
                envelope.payload().flags(),
                Envelope.parseWireTime(envelope.payload().issuedAt()),
                fromCache);
    }

    /** One poll, then self-schedule. Package-private so tests drive cycles synchronously. */
    void pollOnce() {
        String currentEtag;
        synchronized (stateLock) {
            currentEtag = etag;
        }
        FetchOutcome outcome = fetcher.fetchRuleset(currentEtag);
        if (closed) {
            firstAttempt.countDown();
            return;
        }
        record(outcome);
        firstAttempt.countDown();

        int failures;
        synchronized (stateLock) {
            failures = consecutiveFailures;
        }
        long delayMillis;
        if (failures == 0) {
            delayMillis = Backoff.pollDelayMillis(configuration.pollInterval(), random);
        } else {
            delayMillis = Backoff.retryDelayWithServerHintMillis(
                    outcome.kind() == FetchKind.RATE_LIMITED ? outcome.retryAfterSeconds() : 0,
                    failures,
                    random);
        }
        if (closed) {
            return;
        }
        try {
            scheduler.schedule(this::pollOnce, delayMillis, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.RejectedExecutionException raced) {
            // close() shut the scheduler down between the check and the schedule — done.
        }
    }

    /** Turns one fetch outcome into snapshot/cache/diagnostics updates — the ONLY mutation site. */
    void record(FetchOutcome outcome) {
        Instant at = now.get();
        if (outcome.kind() == FetchKind.SUCCESS) {
            Result result = Verifier.verify(outcome.raw(), configuration.signature(),
                    // A live response past expiry is the replay window — refused.
                    new Expectations(configuration.environment(), at, true));
            if (result.envelope() == null) {
                // A rejected envelope never dislodges the snapshot or the cache: the last
                // verified state keeps serving, and the rejection is one diagnostics()
                // read away.
                synchronized (stateLock) {
                    lastFetchAt = at;
                    lastFetchStatus = "rejectedEnvelope";
                    lastRejection = result.code();
                    consecutiveFailures++;
                }
                return;
            }
            Boolean stored = null;
            if (cache != null) {
                stored = cache.store(result.envelope().raw());
            }
            snapshot = snapshotOf(result.envelope(), false);
            synchronized (stateLock) {
                lastFetchAt = at;
                lastFetchStatus = "fresh";
                lastRejection = null;
                etag = outcome.etag();
                consecutiveFailures = 0;
                if (stored != null) {
                    cacheState = stored ? "stored" : "storeFailed";
                }
            }
            networkReady.countDown();
            return;
        }

        String status = switch (outcome.kind()) {
            case NOT_MODIFIED -> "notModified";
            case UNAUTHORIZED -> "unauthorized";
            case RATE_LIMITED -> "rateLimited";
            case SERVER_ERROR -> "serverError";
            case RESPONSE_TOO_LARGE -> "responseTooLarge";
            case UNEXPECTED_STATUS -> "unexpectedStatus";
            default -> "transportError";
        };
        synchronized (stateLock) {
            lastFetchAt = at;
            lastFetchStatus = status;
            if (outcome.kind() == FetchKind.NOT_MODIFIED) {
                // The steady state of a polling fleet: the cached ruleset is current.
                consecutiveFailures = 0;
            } else {
                // Including 401/403 — a revoked key. The contract's instruction: answered
                // like any failed fetch — keep evaluating with the last downloaded
                // ruleset, indefinitely, until given a new key.
                consecutiveFailures++;
            }
        }
    }

    /** The getters' shared path: one volatile read, kind check, evaluate. */
    private Decoded value(String flagKey, Context context, String wantKind) {
        Snapshot current = snapshot;
        if (current == null) {
            fallbackNoSnapshot.increment();
            return Decoded.NO_VALUE;
        }
        FlagConfig config = current.flags().get(flagKey);
        if (config == null) {
            // Absence means the flag does not exist or was archived: the caller's
            // fallback is the contract's answer.
            fallbackUnknownFlag.increment();
            return Decoded.NO_VALUE;
        }
        if (!config.kind().equals(wantKind)) {
            // Asking boolValue of a string flag is a caller bug, but never a throw:
            // fallback, and the mismatch is visible on diagnostics.
            fallbackKindMismatch.increment();
            return Decoded.NO_VALUE;
        }
        Decoded result = Evaluator.evaluate(config, context.tags(), context.key(), flagKey);
        if (!result.ok()) {
            fallbackNoValue.increment();
            return Decoded.NO_VALUE;
        }
        served.increment();
        return result;
    }
}
