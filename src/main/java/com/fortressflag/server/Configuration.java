package com.fortressflag.server;

import java.time.Duration;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Everything the SDK needs to run. Built once, immutable. The key IS A GENUINE SECRET
 * (server-contract-v1, ADR-0015): the SDK holds it in memory, sends it only on the
 * Authorization header, and the only form of it that may ever reach a log line is
 * {@link #keyPrefix}'s six-character prefix.
 */
public final class Configuration {

    static final String DEFAULT_BASE_URL = "https://edge.fortressflag.com";
    /** The contract's floor. Faster is volunteering to be rate-limited for unchanged values. */
    static final Duration MINIMUM_POLL_INTERVAL = Duration.ofSeconds(30);
    static final Duration DEFAULT_POLL_INTERVAL = Duration.ofSeconds(60);
    static final Duration DEFAULT_HTTP_TIMEOUT = Duration.ofSeconds(10);

    /** Six characters of the random part: enough to tell two keys apart in a log line, far
     * too few to guess the rest (the backend's serverkey.PrefixOf rule). */
    private static final int KEY_PREFIX_VISIBLE_CHARS = 6;

    /** The server's environments_key_format CHECK: 2-32 chars, lowercase letters, digits
     * and hyphens, starting and ending with a letter or digit. */
    private static final Pattern ENVIRONMENT_KEY =
            Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,30}[a-z0-9])?");

    private final String key;
    private final String environment;
    private final String baseUrl;
    private final Duration pollInterval;
    private final String cachePath;
    private final SignaturePolicy signature;
    private final Duration httpTimeout;

    private Configuration(Builder builder, String environment) {
        this.key = builder.key;
        this.environment = environment;
        String url = builder.baseUrl;
        this.baseUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        this.pollInterval =
                builder.pollInterval.compareTo(MINIMUM_POLL_INTERVAL) < 0
                        ? MINIMUM_POLL_INTERVAL
                        : builder.pollInterval;
        this.cachePath = builder.cachePath;
        this.signature = builder.signature;
        this.httpTimeout =
                builder.httpTimeout.isZero() || builder.httpTimeout.isNegative()
                        ? DEFAULT_HTTP_TIMEOUT
                        : builder.httpTimeout;
    }

    /** Java has no keyword arguments; the builder is the Configuration literal. */
    public static Builder builder(String key) {
        return new Builder(key);
    }

    public static final class Builder {
        private final String key;
        private String baseUrl = DEFAULT_BASE_URL;
        private Duration pollInterval = DEFAULT_POLL_INTERVAL;
        private String cachePath = "";
        private SignaturePolicy signature = SignaturePolicy.fortressFlagProduction();
        private Duration httpTimeout = DEFAULT_HTTP_TIMEOUT;

        private Builder(String key) {
            this.key = key == null ? "" : key;
        }

        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        /** Defaults to 60 s — one request per minute per process, so a kill switch reaches
         * a fleet within a minute — floored at the contract's 30 (ADR-0016). ±20% jitter
         * is applied so a fleet does not synchronise. */
        public Builder pollInterval(Duration pollInterval) {
            this.pollInterval = pollInterval;
            return this;
        }

        /** Opts in to the durable cache. Unset — the default — means in-memory only: where
         * a server process may write is the operator's call, and this SDK writes nothing
         * unasked (ADR-0016). The file holds only the ruleset envelope, never the key and
         * never any evaluation context. */
        public Builder cachePath(String cachePath) {
            this.cachePath = cachePath;
            return this;
        }

        /** Defaults to {@link SignaturePolicy#fortressFlagProduction()} — fail closed
         * against the production key (ADR-0025). Pass {@link SignaturePolicy#disabled()}
         * explicitly for a local backend without signing keys, or
         * {@link SignaturePolicy#required(Map)} with the staging key. */
        public Builder signature(SignaturePolicy signature) {
            this.signature = signature;
            return this;
        }

        /** Short on purpose: a slow ruleset fetch must never become the customer's
         * problem — the snapshot keeps answering. */
        public Builder httpTimeout(Duration httpTimeout) {
            this.httpTimeout = httpTimeout;
            return this;
        }

        /** Validates and freezes. The one throw path in the SDK. */
        public Configuration build() {
            String environment = parseKey(key);
            if (environment == null) {
                throw new MalformedKeyException();
            }
            return new Configuration(this, environment);
        }
    }

    /**
     * Validates the configured key's shape and returns the environment it claims, or null.
     *
     * <p>indexOf-based, NOT {@code String.split} — the backend serverkey comment, ported a
     * sixth time, because in Java the bug has its own dialect: {@code split} takes a REGEX
     * and its limit semantics differ from Go's SplitN in ways nobody remembers under
     * pressure. The secret is base64url, whose alphabet includes {@code _}; any parse that
     * loses the remainder rejects roughly three keys in four — silently, fleet-wide,
     * intermittently — and a happy-path key tested by hand would not have contained an
     * underscore. The test suite pins a key with underscores in its secret.
     */
    static String parseKey(String raw) {
        int first = raw.indexOf('_');
        if (first < 0 || !raw.substring(0, first).equals("ffs")) {
            return null;
        }
        int second = raw.indexOf('_', first + 1);
        if (second < 0 || second + 1 >= raw.length()) {
            return null;
        }
        String environment = raw.substring(first + 1, second);
        if (environment.length() < 2 || !ENVIRONMENT_KEY.matcher(environment).matches()) {
            return null;
        }
        return environment;
    }

    /** The non-secret leading part of the key — THE ONLY FORM OF A SERVER KEY THAT MAY
     * EVER BE LOGGED (ADR-0015, ported). Empty for a malformed key. */
    static String keyPrefix(String raw) {
        String environment = parseKey(raw);
        if (environment == null) {
            return "";
        }
        int head = "ffs_".length() + environment.length() + 1;
        if (raw.length() < head + KEY_PREFIX_VISIBLE_CHARS) {
            return "";
        }
        return raw.substring(0, head + KEY_PREFIX_VISIBLE_CHARS);
    }

    String key() {
        return key;
    }

    String environment() {
        return environment;
    }

    String baseUrl() {
        return baseUrl;
    }

    Duration pollInterval() {
        return pollInterval;
    }

    String cachePath() {
        return cachePath;
    }

    SignaturePolicy signature() {
        return signature;
    }

    Duration httpTimeout() {
        return httpTimeout;
    }
}
