package com.fortressflag.server;

/** What {@link Client#start} reports. Never an exception. */
public enum StartOutcome {
    READY("ready"),
    CACHE_ONLY("cache-only"),
    TIMED_OUT("timed-out-serving-defaults");

    private final String wire;

    StartOutcome(String wire) {
        this.wire = wire;
    }

    @Override
    public String toString() {
        return wire;
    }
}
