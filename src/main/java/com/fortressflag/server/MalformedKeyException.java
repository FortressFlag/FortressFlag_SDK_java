package com.fortressflag.server;

/**
 * The configured value is not shaped like an ffs_ server key. Thrown by
 * {@link FortressFlag#create} — the ONE place this SDK throws, at construction, before the
 * customer's process serves anything (Founding §8.1). The message deliberately never
 * echoes the configured value: a mistyped secret pasted into the wrong field must not land
 * in an exception string that lands in a log.
 */
public final class MalformedKeyException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    MalformedKeyException() {
        super("fortressflag: key is not of the form ffs_<environment>_<secret>");
    }
}
