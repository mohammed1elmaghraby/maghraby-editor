package org.maghraby;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Backend of the chat side panel.
 *
 * The editor never talks to an AI provider itself. A message is handed to an external command
 * (environment variable {@code MAGHRABY_CHAT_CMD}, for example {@code crush run}); the prompt is
 * appended as the last argument and whatever the command prints on stdout becomes the answer.
 * The command runs on a background thread, so the editor never blocks while the model thinks.
 */
final class Chat {

    enum Role { USER, ASSISTANT, ERROR }

    static final class Message {
        final Role role;
        final String text;
        final String meta; // small dim line under an answer ("◇ crush in 3s"), may be empty

        Message(Role role, String text, String meta) {
            this.role = role;
            this.text = text;
            this.meta = meta;
        }
    }

    private static final long TIMEOUT_SECONDS = 300;
    private static final int MAX_OUTPUT_CHARS = 20_000;

    private final List<Message> messages = new CopyOnWriteArrayList<>();
    private final Runnable onChange;
    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Chat-Backend");
        t.setDaemon(true);
        return t;
    });

    private volatile boolean busy = false;
    private volatile long busySince = 0;
    private volatile Process current = null;
    private volatile boolean cancelled = false;

    /** @param onChange called from a background thread whenever the visible conversation changes. */
    Chat(Runnable onChange) {
        this.onChange = onChange;
        messages.add(new Message(Role.ASSISTANT, "Hello! What can I help you with?", ""));
    }

    List<Message> messages() { return messages; }

    boolean busy() { return busy; }

    long busySince() { return busySince; }

    void clear() {
        cancel();
        messages.clear();
        messages.add(new Message(Role.ASSISTANT, "Hello! What can I help you with?", ""));
    }

    /** The configured command split into arguments, or null when nothing is configured. */
    static List<String> configuredCommand() {
        String raw = System.getenv("MAGHRABY_CHAT_CMD");
        if (raw == null || raw.trim().isEmpty()) return null;
        List<String> parts = splitArgs(raw.trim());
        return parts.isEmpty() ? null : parts;
    }

    /** Short name for the panel ("crush", "claude"...), or "no backend". */
    static String backendLabel() {
        List<String> cmd = configuredCommand();
        if (cmd == null) return "no backend";
        String first = cmd.get(0);
        int slash = Math.max(first.lastIndexOf('/'), first.lastIndexOf('\\'));
        return slash >= 0 ? first.substring(slash + 1) : first;
    }

    /**
     * Shows {@code display} as the user's message and asks the backend {@code prompt} (which may
     * carry extra context such as the open file). Returns false when a request is already running.
     */
    boolean send(String display, String prompt, Path workDir) {
        if (busy) return false;
        messages.add(new Message(Role.USER, display, ""));
        busy = true;
        busySince = System.currentTimeMillis();
        onChange.run();
        exec.execute(() -> {
            try {
                run(prompt, workDir);
            } finally {
                busy = false;
                current = null;
                onChange.run();
            }
        });
        return true;
    }

    /** Kills the running request, if any. */
    void cancel() {
        Process p = current;
        if (p != null) {
            cancelled = true;
            p.destroyForcibly();
        }
    }

    void close() {
        cancel();
        exec.shutdownNow();
    }

    // ------------------------------------------------------------------

    private void run(String prompt, Path workDir) {
        List<String> cmd = configuredCommand();
        if (cmd == null) {
            messages.add(new Message(Role.ERROR,
                    "No chat backend configured. Set MAGHRABY_CHAT_CMD to a command that takes the "
                            + "prompt as its last argument and prints the answer, then restart. "
                            + "Example: export MAGHRABY_CHAT_CMD=\"crush run\"", ""));
            return;
        }
        List<String> full = new ArrayList<>(cmd);
        full.add(prompt);

        long start = System.currentTimeMillis();
        cancelled = false;
        try {
            Process p = new ProcessBuilder(full)
                    .directory(workDir == null ? null : workDir.toFile())
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            current = p;
            p.getOutputStream().close(); // no stdin: the prompt is an argument
            byte[] raw = p.getInputStream().readAllBytes();
            boolean finished = p.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (cancelled) {
                messages.add(new Message(Role.ERROR, "Cancelled.", ""));
                return;
            }
            if (!finished) {
                p.destroyForcibly();
                messages.add(new Message(Role.ERROR, "Timed out after " + TIMEOUT_SECONDS + "s.", ""));
                return;
            }
            String text = clean(new String(raw, StandardCharsets.UTF_8));
            long secs = Math.max(1, Math.round((System.currentTimeMillis() - start) / 1000.0));
            String meta = "◇ " + backendLabel() + " in " + secs + "s";
            if (text.isEmpty()) {
                messages.add(new Message(Role.ERROR,
                        "The command printed nothing (exit code " + p.exitValue() + ").", meta));
            } else if (p.exitValue() != 0) {
                messages.add(new Message(Role.ERROR, text + "\n(exit code " + p.exitValue() + ")", meta));
            } else {
                messages.add(new Message(Role.ASSISTANT, text, meta));
            }
        } catch (IOException e) {
            messages.add(new Message(Role.ERROR, "Could not start '" + cmd.get(0) + "': " + e.getMessage(), ""));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Strips ANSI escapes and control characters, trims, and caps the length. */
    private static String clean(String s) {
        s = s.replaceAll("\u001B\\[[0-9;?]*[ -/]*[@-~]", "")
                .replaceAll("\u001B\\][^\u0007]*\u0007", "")
                .replace("\r\n", "\n").replace('\r', '\n').replace("\t", "    ");
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\n' || c >= 0x20 && !(c >= 0x7F && c < 0xA0)) sb.append(c);
        }
        String t = sb.toString().strip();
        if (t.length() > MAX_OUTPUT_CHARS) t = t.substring(0, MAX_OUTPUT_CHARS) + "\n… (truncated)";
        return t;
    }

    private static List<String> splitArgs(String line) {
        List<String> args = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        char quote = 0;
        boolean has = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quote != 0) {
                if (c == quote) quote = 0; else cur.append(c);
            } else if (c == '"' || c == '\'') {
                quote = c;
                has = true;
            } else if (Character.isWhitespace(c)) {
                if (has || cur.length() > 0) { args.add(cur.toString()); cur.setLength(0); has = false; }
            } else {
                cur.append(c);
            }
        }
        if (has || cur.length() > 0) args.add(cur.toString());
        return args;
    }
}