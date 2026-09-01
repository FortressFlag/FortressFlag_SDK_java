/**
 * The FortressFlag Java server SDK (backend ADR-0018).
 *
 * <p>The public surface is this package's public types — everything else is package-private
 * or in internal packages. {@code FortressFlag.create} is the one place the SDK throws;
 * after construction, getters never throw, the poller never blocks JVM exit, and every
 * failure resolves to the caller's fallback with the reason on {@code diagnostics()}.
 */
package com.fortressflag.server;
