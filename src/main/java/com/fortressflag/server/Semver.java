package com.fortressflag.server;

/**
 * Dotted-numeric version comparison for the semver_* operators — a PORT of the backend's
 * internal/semver, whose package comment is the specification. Deliberately NOT Semantic
 * Versioning 2.0.0: targeting needs "is this version at least 2.0?", and the semantics are
 * a contract shared with every SDK (contract-v1.md, ADR-0004; pinned by
 * vectors/evaluation.json). Split on '.'; compare numeric components left to right; a
 * missing component is 0 ("2.0" == "2.0.0"); components are non-negative base-10 integers,
 * anything else does not parse — and non-parsing MEANS the condition does not hold, never
 * an error.
 */
final class Semver {
    private Semver() {}

    /**
     * Ten digits already exceed int32; a longer run is not a version, and refusing it
     * keeps a hostile tag value from turning the comparison into big-integer work.
     */
    private static final int MAX_COMPONENT_DIGITS = 10;

    /** Parses, or returns null — the caller's question is "is this comparable?". */
    static long[] parse(String s) {
        if (s.isEmpty()) {
            return null;
        }
        String[] parts = s.split("\\.", -1);
        long[] version = new long[parts.length];
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (part.isEmpty() || part.length() > MAX_COMPONENT_DIGITS) {
                return null;
            }
            long n = 0;
            for (int j = 0; j < part.length(); j++) {
                char c = part.charAt(j);
                if (c < '0' || c > '9') {
                    return null;
                }
                n = n * 10 + (c - '0');
            }
            version[i] = n;
        }
        return version;
    }

    /**
     * -1, 0 or 1 as a is less than, equal to, or greater than b. Missing components read
     * as 0 — what makes "2.0" equal "2.0.0", and what a naive length comparison gets
     * wrong.
     */
    static int compare(long[] a, long[] b) {
        int n = Math.max(a.length, b.length);
        for (int i = 0; i < n; i++) {
            long av = i < a.length ? a[i] : 0;
            long bv = i < b.length ? b[i] : 0;
            if (av < bv) {
                return -1;
            }
            if (av > bv) {
                return 1;
            }
        }
        return 0;
    }
}
