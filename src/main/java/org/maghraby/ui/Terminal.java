package org.maghraby.ui;

import java.io.BufferedOutputStream;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.maghraby.core.State.*;
import static org.maghraby.ui.Ansi.*;

/**
 * Raw terminal handling: stty, alternate screen, size tracking, and handing the terminal to child programs.
 */
public final class Terminal {
    private Terminal() {}

    public static volatile int termRows = 24;
    public static volatile int termCols = 80;

    // Concurrency lock: Rendering is paused while an external program owns the terminal
    public static final Object RENDER_LOCK = new Object();

    public static volatile boolean renderPaused = false;
    public static String originalStty = null;
    public static final AtomicBoolean terminalRestored = new AtomicBoolean(false);
    public static final PrintStream out = createOut();

    public static PrintStream createOut() {
        try {
            return new PrintStream(new BufferedOutputStream(new FileOutputStream(FileDescriptor.out), 1 << 16),
                    false, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return System.out;
        }
    }

    /** Executes standard `stty` commands to manipulate terminal properties. */
    public static String stty(String args) {
        try {
            Process p = new ProcessBuilder("sh", "-c", "stty " + args + " < /dev/tty")
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            p.getOutputStream().close();
            String result = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int code = p.waitFor();
            return code == 0 ? result.trim() : null;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    public static void updateTerminalSize() {
        String s = stty("size");
        if (s == null) return;
        String[] p = s.split("\\s+");
        if (p.length != 2) return;
        try {
            int r = Integer.parseInt(p[0]);
            int c = Integer.parseInt(p[1]);
            if (r > 0 && c > 0 && (r != termRows || c != termCols)) {
                termRows = r;
                termCols = c;
                forceClear.set(true);
                needsRedraw = true;
            }
        } catch (NumberFormatException ignored) { }
    }

    public static void resizeWatcher() {
        while (running) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                return;
            }
            if (!renderPaused) updateTerminalSize();
        }
    }

    public static void enterEditorScreen() {
        stty("raw -echo");
        out.print(ANSI_ENTER_ALT_SCREEN + ANSI_CLEAR_SCREEN + ANSI_HIDE_CURSOR);
        out.flush();
    }

    /** Surrenders terminal control to user programs executed via :run */
    public static void suspendTerminal() {
        synchronized (RENDER_LOCK) {
            renderPaused = true;
        }
        out.print(ANSI_RESET + ESC + "[0 q" + ANSI_SHOW_CURSOR + ANSI_LEAVE_ALT_SCREEN);
        out.flush();
        stty(originalStty);
    }

    /** Regains terminal control after a user program exits. */
    public static void resumeTerminal() {
        stty("raw -echo");
        out.print(ANSI_ENTER_ALT_SCREEN + ANSI_CLEAR_SCREEN + ANSI_HIDE_CURSOR);
        out.flush();
        updateTerminalSize();
        forceClear.set(true);
        needsRedraw = true;
        renderPaused = false;
    }

    public static void restoreTerminal() {
        if (!terminalRestored.compareAndSet(false, true)) return;
        out.print(ANSI_RESET + ESC + "[0 q" + ANSI_SHOW_CURSOR + ANSI_LEAVE_ALT_SCREEN);
        out.flush();
        if (originalStty != null) {
            if (stty(originalStty) == null) stty("sane");
        }
    }
}
