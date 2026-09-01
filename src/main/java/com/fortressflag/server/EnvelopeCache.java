package com.fortressflag.server;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

/**
 * The opt-in durable cache (ADR-0016): the file named by cachePath holds the last VERIFIED
 * envelope's raw bytes — verbatim, never a re-serialisation (re-serialising would strip
 * future fields and break the signature on reload), never parsed values, never the key,
 * never any evaluation context. The caller re-verifies on load (expiry unenforced — the
 * asymmetry), so poisoning the cache requires forging whatever the transport requires, and
 * the cache inherits every transport guarantee for free.
 *
 * <p>Every failure here degrades to in-memory operation with a note on diagnostics() — a
 * cache problem is never the customer's problem.
 */
final class EnvelopeCache {

    private static final Set<PosixFilePermission> OWNER_ONLY =
            Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    private final Path path;

    EnvelopeCache(String path) {
        this.path = Path.of(path);
    }

    /** The cached envelope bytes, or null when there is nothing usable. A file larger than
     * the transport's own response cap was not written by us; refused before reading. */
    byte[] load() {
        try {
            long size = Files.size(path);
            if (size == 0 || size > Transport.MAX_RESPONSE_BYTES) {
                return null;
            }
            return Files.readAllBytes(path);
        } catch (IOException exception) {
            return null;
        }
    }

    /**
     * Atomically replaces the cache file with raw. Temp-file-in-the-SAME-directory +
     * {@code Files.move}, deliberately: a temp file in the system temp directory makes the
     * move cross-filesystem, which throws {@link AtomicMoveNotSupportedException} (the
     * EXDEV class — trap #8; on Android the equivalent failed silently under SELinux and
     * every write was lost). ATOMIC_MOVE is tried first and REPLACE_EXISTING is the
     * non-atomic fallback for filesystems that cannot promise atomicity even same-dir —
     * degraded, never a throw. Owner-only permissions are set EXPLICITLY where POSIX
     * applies: {@code createTempFile} is 0600 on POSIX, but relying on a default that a
     * refactor to another primitive would silently lose is the recorded bug class; on
     * non-POSIX filesystems (Windows) the ACL default stands, documented here.
     */
    boolean store(byte[] raw) {
        if (raw.length == 0 || raw.length > Transport.MAX_RESPONSE_BYTES) {
            return false;
        }
        Path directory = path.toAbsolutePath().getParent();
        if (directory == null) {
            return false;
        }
        Path temp = null;
        try {
            temp = Files.createTempFile(directory, ".fortressflag-cache-", ".tmp");
            try {
                Files.setPosixFilePermissions(temp, OWNER_ONLY);
            } catch (UnsupportedOperationException ignored) {
                // Non-POSIX filesystem: the platform ACL default stands.
            }
            Files.write(temp, raw);
            try {
                Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException fallback) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException exception) {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // Best-effort cleanup; nothing above cares.
                }
            }
            return false;
        }
    }
}
