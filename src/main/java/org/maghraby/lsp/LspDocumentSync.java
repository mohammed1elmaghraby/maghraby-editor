package org.maghraby.lsp;

import org.maghraby.util.Json;
import org.maghraby.lsp.LspServers;
import org.maghraby.core.TextEditor;
import org.maghraby.input.EditListener;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import java.util.Comparator;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Keeps one open document in sync with a language server without ever blocking the editor.
 *
 * The editor thread only appends a small change record to a list (see {@link EditListener}). A single
 * background thread debounces those records and sends them as ONE textDocument/didChange.
 *
 * Lock order is always  editor monitor -> {@code lock}.  The background thread never takes the editor
 * monitor while holding {@code lock}, so the two threads cannot deadlock.
 */
public final class LspDocumentSync implements EditListener, AutoCloseable {

    private static final long DEBOUNCE_MS = 150;
    private static final String[] ROOT_MARKERS = {
            ".git", "go.mod", "pom.xml", "build.gradle", "build.gradle.kts", "compile_commands.json",
            "pyproject.toml", "setup.py"
    };

    private final LspClient client;
    private final TextEditor editor;
    private final String languageId;
    private final ScheduledExecutorService exec;

    private final Object lock = new Object();
    // --- guarded by lock ---
    private boolean opened = false;
    private String uri = null;
    private int version = 0;
    private List<Object> pending = new ArrayList<>(); // incremental changes, in edit order
    private boolean changed = false;                    // something changed since the last flush
    private ScheduledFuture<?> flushTask = null;

    public LspDocumentSync(LspClient client, TextEditor editor, String languageId) {
        this.client = client;
        this.editor = editor;
        this.languageId = languageId;
        this.exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "LSP-Sync");
            t.setDaemon(true);
            return t;
        });
        editor.setChangeListener(this);
    }

    /**
     * Finds a server for the file and starts it. Returns null when there is no server for this language
     * or it could not be launched. The server is started in the background; diagnostics arrive through
     * the listener whenever it is ready. Call {@link #open(Path)} afterwards.
     */
    public static LspDocumentSync tryStart(String filename, TextEditor editor, LspClient.Listener listener) {
        Path file;
        try {
            file = Paths.get(filename.trim()).toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            return null;
        }
        Path root = findRoot(file);
        LspServers.Spec spec = LspServers.forFile(file.getFileName().toString(), root);
        if (spec == null) return null;
        try {
            LspClient client = LspClient.start(spec.command, root, listener);
            return new LspDocumentSync(client, editor, spec.languageId);
        } catch (IOException e) {
            return null;
        }
    }

    public LspClient client() { return client; }

    public String currentUri() {
        synchronized (lock) { return uri; }
    }

    /** True when {@code diagnosticUri} refers to the document that is currently open. */
    public boolean isCurrent(String diagnosticUri) {
        String cur = currentUri();
        if (cur == null || diagnosticUri == null) return false;
        if (cur.equals(diagnosticUri)) return true;
        Path a = LspClient.pathOf(cur);
        Path b = LspClient.pathOf(diagnosticUri);
        return a != null && a.equals(b);
    }

    // ------------------------------------------------------------------
    // Document lifecycle (all of it runs on the sync thread)
    // ------------------------------------------------------------------

    /**
     * Opens the editor's CURRENT content as {@code file}. Call it after loading a file or after a
     * "save as" to a new name; an already open document is closed first. Returns immediately.
     */
    public void open(Path file) {
        run(() -> doOpen(file));
    }

    private void doOpen(Path file) {
        if (!client.awaitReady(60, TimeUnit.SECONDS)) return; // jdtls can take a while
        closeCurrent();

        String newUri = LspClient.uriOf(file);
        String text;
        // Snapshot + "start listening" must be atomic with respect to edits: an edit either is in the
        // snapshot (and dropped from the queue) or comes after it (and is queued), never both or neither.
        synchronized (editor) {
            text = editor.getFullText();
            synchronized (lock) {
                opened = true;
                uri = newUri;
                version = 1;
                pending = new ArrayList<>();
                changed = false;
            }
        }
        client.sendNotification("textDocument/didOpen", Json.obj("textDocument",
                Json.obj("uri", newUri, "languageId", languageId, "version", 1, "text", text)));
    }

    private void closeCurrent() {
        String old;
        synchronized (lock) {
            if (!opened) return;
            opened = false;
            old = uri;
            pending = new ArrayList<>();
            changed = false;
            if (flushTask != null) { flushTask.cancel(false); flushTask = null; }
        }
        client.sendNotification("textDocument/didClose", Json.obj("textDocument", Json.obj("uri", old)));
    }

    // ------------------------------------------------------------------
    // EditListener: called on the editor thread, must stay tiny
    // ------------------------------------------------------------------

    @Override
    public void onInsert(int line, int col, String text) {
        synchronized (lock) {
            if (!opened) return;
            if (client.syncKind() == 2) {
                pending.add(Json.obj("range", range(line, col, line, col), "text", text));
            }
            changed = true;
            scheduleFlushLocked();
        }
    }

    @Override
    public void onDelete(int startLine, int startCol, int endLine, int endCol) {
        synchronized (lock) {
            if (!opened) return;
            if (client.syncKind() == 2) {
                pending.add(Json.obj("range", range(startLine, startCol, endLine, endCol), "text", ""));
            }
            changed = true;
            scheduleFlushLocked();
        }
    }

    private void scheduleFlushLocked() {
        if (flushTask != null) flushTask.cancel(false); // debounce: restart the timer on every edit
        try {
            flushTask = exec.schedule(this::flush, DEBOUNCE_MS, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ignored) {
            flushTask = null; // shutting down
        }
    }

    private void flush() {
        List<Object> changes;
        String u;
        int ver;
        synchronized (lock) {
            flushTask = null;
            if (!opened || !changed) return;
            changed = false;
            changes = pending;
            pending = new ArrayList<>();
            u = uri;
            ver = ++version;
        }
        // Outside `lock`: getFullText() takes the editor monitor.
        List<Object> contentChanges = client.syncKind() == 2
                ? changes
                : Json.list(Json.obj("text", editor.getFullText()));
        client.sendNotification("textDocument/didChange", Json.obj(
                "textDocument", Json.obj("uri", u, "version", ver),
                "contentChanges", contentChanges));
    }

    private static Map<String, Object> range(int sl, int sc, int el, int ec) {
        return Json.obj(
                "start", Json.obj("line", sl, "character", sc),
                "end", Json.obj("line", el, "character", ec));
    }

    // ------------------------------------------------------------------
    // Shutdown
    // ------------------------------------------------------------------

    @Override
    public void close() {
        editor.removeChangeListener(this); // no-op if a newer sync already replaced us
        try {
            exec.execute(this::closeCurrent);
        } catch (RejectedExecutionException ignored) { }
        exec.shutdown();
        try {
            exec.awaitTermination(500, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        client.close();
    }

    private void run(Runnable r) {
        try {
            exec.execute(r);
        } catch (RejectedExecutionException ignored) { }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Nearest ancestor containing a project marker (go.mod, .git, pom.xml...), else the file's folder. */
    public static Path findRoot(Path file) {
        Path dir = file.getParent();
        for (Path p = dir; p != null; p = p.getParent()) {
            for (String marker : ROOT_MARKERS) {
                if (Files.exists(p.resolve(marker))) return p;
            }
        }
        return dir == null ? Paths.get(".").toAbsolutePath().normalize() : dir;
    }

    // ------------------------------------------------------------------
    // Completion
    // ------------------------------------------------------------------

    /** One entry of the completion popup. */
    public static final class Completion {
        public final String label;      // what the popup shows
        public final String detail;     // signature / type, may be empty
        public final String insertText; // what replaces the typed prefix
        public final int kind;          // LSP CompletionItemKind (1..25), 0 = unknown
        public final int replaceFromCol; // column on the cursor line where the replacement starts

        public Completion(String label, String detail, String insertText, int kind, int replaceFromCol) {
            this.label = label;
            this.detail = detail;
            this.insertText = insertText;
            this.kind = kind;
            this.replaceFromCol = replaceFromCol;
        }
    }

    private final AtomicInteger completionSeq = new AtomicInteger();

    /** Invalidates any completion request still in flight (its callback will never fire). */
    public void cancelSuggestions() {
        completionSeq.incrementAndGet();
    }

    /**
     * Asks the server for completions at (line, col). {@code prefix} is the identifier text just before the
     * cursor; it is used to filter and rank. The callback runs on a background thread, and only for the
     * LATEST request: answers that arrive after the user typed on are dropped.
     *
     * Pending edits are flushed first, otherwise the server would complete against a stale document.
     */
    public void requestSuggestions(int line, int col, String prefix, Consumer<List<Completion>> callback) {
        if (!client.isAlive()) return;
        final int seq = completionSeq.incrementAndGet();
        final String pfx = prefix == null ? "" : prefix;

        run(() -> {
            if (seq != completionSeq.get()) return; // superseded while queued
            if (!client.awaitReady(0, TimeUnit.MILLISECONDS)) return;
            String uriStr = currentUri();
            if (uriStr == null) return;
            flush(); // make the server see the character that was just typed

            Map<String, Object> params = Json.obj(
                    "textDocument", Json.obj("uri", uriStr),
                    "position", Json.obj("line", line, "character", col));

            client.request("textDocument/completion", params).whenComplete((result, error) -> {
                if (error != null || result == null || seq != completionSeq.get()) return;
                List<Object> items = result instanceof List
                        ? Json.asList(result)
                        : (result instanceof Map ? Json.asList(Json.asMap(result).get("items")) : null);
                if (items == null) return;

                List<Object[]> ranked = new ArrayList<>(); // {sortKey, Completion}
                String lowerPrefix = pfx.toLowerCase(Locale.ROOT);
                for (Object o : items) {
                    Map<String, Object> m = Json.asMap(o);
                    if (m == null) continue;
                    String label = Json.asString(m.get("label"));
                    if (label == null || label.isEmpty()) continue;

                    String filter = Json.asString(m.get("filterText"));
                    if (filter == null) filter = label;
                    if (!lowerPrefix.isEmpty() && !filter.toLowerCase(Locale.ROOT).startsWith(lowerPrefix)) continue;

                    String insert = Json.asString(m.get("insertText"));
                    int replaceFrom = col - pfx.length();
                    Map<String, Object> edit = Json.asMap(m.get("textEdit"));
                    if (edit != null) {
                        String nt = Json.asString(edit.get("newText"));
                        if (nt != null) insert = nt;
                        Map<String, Object> r = Json.asMap(edit.get("range"));
                        if (r == null) r = Json.asMap(edit.get("replace"));
                        Map<String, Object> rs = r == null ? null : Json.asMap(r.get("start"));
                        Map<String, Object> re = r == null ? null : Json.asMap(r.get("end"));
                        if (rs != null && re != null
                                && Json.asInt(rs.get("line"), -1) == line && Json.asInt(re.get("line"), -1) == line) {
                            replaceFrom = Json.asInt(rs.get("character"), replaceFrom);
                        }
                    }
                    if (insert == null) insert = label;
                    if (Json.asInt(m.get("insertTextFormat"), 1) == 2) insert = stripSnippet(insert);

                    String detail = Json.asString(m.get("detail"));
                    String sort = Json.asString(m.get("sortText"));
                    Completion c = new Completion(label, detail == null ? "" : detail, insert,
                            Json.asInt(m.get("kind"), 0), Math.max(0, replaceFrom));
                    ranked.add(new Object[]{sort == null ? label : sort, c});
                }
                ranked.sort(Comparator.comparing(a -> (String) a[0]));

                List<Completion> out = new ArrayList<>();
                for (Object[] r : ranked) {
                    out.add((Completion) r[1]);
                    if (out.size() >= 50) break;
                }
                if (seq == completionSeq.get()) callback.accept(out);
            });
        });
    }

    /** "foo(${1:a}, ${2:b})$0" -> "foo(a, b)". */
    private static String stripSnippet(String s) {
        return s.replaceAll("\\$\\{\\d+:([^}]*)}", "$1")
                .replaceAll("\\$\\{\\d+}", "")
                .replaceAll("\\$\\d+", "");
    }
}