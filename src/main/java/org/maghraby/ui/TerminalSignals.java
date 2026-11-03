package org.maghraby.ui;

import sun.misc.Signal;
import sun.misc.SignalHandler;

/**
 * Small wrapper around sun.misc.Signal (WINCH / INT).
 *
 * Uses direct calls (no reflection / Proxy) on purpose: the editor is shipped as a
 * GraalVM native image, where reflection and dynamic proxies need explicit config
 * and would silently fail. If signals are unavailable, registration is a no-op.
 */
public final class TerminalSignals {
    private TerminalSignals() {}

    public interface Registration extends AutoCloseable {
        @Override void close();
    }

    public static Registration onWindowResize(Runnable callback) {
        return install("WINCH", callback);
    }

    public static Registration ignoreInterruptTemporarily() {
        return install("INT", () -> { });
    }

    private static Registration install(String name, Runnable callback) {
        try {
            Signal signal = new Signal(name);
            SignalHandler previous = Signal.handle(signal, sig -> callback.run());
            return () -> {
                try { Signal.handle(signal, previous); } catch (Throwable ignored) { }
            };
        } catch (Throwable unavailable) {
            return () -> { };
        }
    }
}
