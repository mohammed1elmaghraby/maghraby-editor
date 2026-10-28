package org.maghraby.lsp;

import org.maghraby.util.Json;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Minimal asynchronous Language Server Protocol client (JSON-RPC 2.0 over the server's stdin/stdout).
 *
 * Threading model:
 *  - ONE daemon reader thread parses messages and completes futures / fires listener callbacks.
 *  - Writers (any thread) serialize on a lock; nothing here ever touches the UI thread.
 *  - The server's stderr goes to a log file, never to the terminal (that would corrupt the screen),
 *    and can never fill a pipe and stall the server.
 *
 * Listener callbacks run on the reader thread: they must be quick and thread-safe.
 */
public final class LspClient implements AutoCloseable {

    // ------------------------------------------------------------------
    // Public types
    // ------------------------------------------------------------------

    public static final class Diagnostic {
        public final int line, col, endLine, endCol; // 0-based, columns in UTF-16 units (= Java chars)
        public final int severity;                   // 1 error, 2 warning, 3 info, 4 hint
        public final String message;
        public final String source;

        Diagnostic(int line, int col, int endLine, int endCol, int severity, String message, String source) {
            this.line = line;
            this.col = col;
            this.endLine = endLine;
            this.endCol = endCol;
            this.severity = severity;
            this.message = message;
            this.source = source;
        }
    }

    public interface Listener {
        void onDiagnostics(String uri, List<Diagnostic> diagnostics);

        /** The server process ended (crash, killed by Ctrl+C in the terminal, normal exit...). */
        default void onExit(String reason) {}
    }

    /** The server answered a request with a JSON-RPC error. */
    public static final class ResponseException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public final int code;
        public ResponseException(int code, String message) {
            super(message);
            this.code = code;
        }
    }

    // ------------------------------------------------------------------
    // State
    // ------------------------------------------------------------------

    private final Process process;
    private final OutputStream toServer;
    private final InputStream fromServer;
    private final Listener listener;
    private final Path rootDir;
    private final Path logFile;
    private final String rootUri;

    private final Object writeLock = new Object();
    private final AtomicInteger nextId = new AtomicInteger(1);
    private final ConcurrentHashMap<Integer, CompletableFuture<Object>> pending = new ConcurrentHashMap<>();
    private final CompletableFuture<Void> ready = new CompletableFuture<>();

    private volatile boolean closed = false;
    private volatile int syncKind = 2; // 0 none, 1 full, 2 incremental (from the server's capabilities)
    private volatile Map<String, Object> serverCapabilities;

    private LspClient(Process process, Path rootDir, Listener listener, Path logFile) {
        this.process = process;
        this.toServer = process.getOutputStream();
        this.fromServer = new BufferedInputStream(process.getInputStream(), 1 << 16);
        this.listener = listener;
        this.rootDir = rootDir;
        this.logFile = logFile;
        this.rootUri = rootDir.toUri().toString();
    }

    /**
     * Starts the server and begins the initialize handshake in the background. Returns immediately;
     * use {@link #ready()} / {@link #awaitReady} to know when it is usable.
     */
    public static LspClient start(List<String> command, Path rootDir, Listener listener) throws IOException {
        Path log = Files.createTempFile("maghraby-lsp-", ".log");
        Process p = new ProcessBuilder(command)
                .directory(rootDir.toFile())
                .redirectError(ProcessBuilder.Redirect.appendTo(log.toFile()))
                .start();
        LspClient c = new LspClient(p, rootDir, listener, log);
        Thread reader = new Thread(c::readLoop, "LSP-Reader");
        reader.setDaemon(true);
        reader.start();
        c.beginInitialize();
        return c;
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    public Path logFile() { return logFile; }

    public boolean isAlive() { return !closed && process.isAlive(); }

    /** Completes when initialize/initialized finished (exceptionally if the handshake failed). */
    public CompletableFuture<Void> ready() { return ready; }

    /** Blocks up to the timeout. Intended for background threads only, never the UI thread. */
    public boolean awaitReady(long timeout, TimeUnit unit) {
        try {
            ready.get(timeout, unit);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 0 = None, 1 = Full, 2 = Incremental. */
    public int syncKind() { return syncKind; }

    public Map<String, Object> serverCapabilities() { return serverCapabilities; }

    /** Sends a request; the future completes with the "result" (maybe null) or fails with ResponseException. */
    public CompletableFuture<Object> request(String method, Object params) {
        CompletableFuture<Object> f = new CompletableFuture<>();
        if (closed) {
            f.completeExceptionally(new IOException("LSP client is closed"));
            return f;
        }
        int id = nextId.getAndIncrement();
        pending.put(id, f);
        try {
            send(Json.obj("jsonrpc", "2.0", "id", id, "method", method, "params", params));
        } catch (IOException e) {
            pending.remove(id);
            f.completeExceptionally(e);
        }
        return f;
    }

    public void sendNotification(String method, Object params) {
        if (closed) return;
        try {
            send(Json.obj("jsonrpc", "2.0", "method", method, "params", params));
        } catch (IOException e) {
            // The reader thread notices the dead pipe and reports onExit.
        }
    }

    public static String uriOf(Path file) {
        return file.toAbsolutePath().normalize().toUri().toString();
    }

    /** Best-effort conversion; returns null for non-file or malformed URIs. */
    public static Path pathOf(String uri) {
        try {
            return Paths.get(URI.create(uri)).toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    public void close() {
        if (closed) {
            // The reader thread marks us closed when the server dies on its own; make sure nothing lingers.
            if (process.isAlive()) process.destroyForcibly();
            try { Files.deleteIfExists(logFile); } catch (IOException ignored) { }
            return;
        }
        try {
            if (ready.isDone() && !ready.isCompletedExceptionally() && process.isAlive()) {
                try {
                    request("shutdown", null).get(1, TimeUnit.SECONDS);
                } catch (Exception ignored) { }
                sendNotification("exit", null);
            }
        } finally {
            closed = true;
            failAll(new IOException("LSP client closed"));
            try {
                if (!process.waitFor(500, TimeUnit.MILLISECONDS)) process.destroyForcibly();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
            try { Files.deleteIfExists(logFile); } catch (IOException ignored) { }
        }
    }

    // ------------------------------------------------------------------
    // Handshake
    // ------------------------------------------------------------------

    private void beginInitialize() {
        String rootName = rootDir.getFileName() == null ? "workspace" : rootDir.getFileName().toString();
        Map<String, Object> params = Json.obj(
                "processId", ProcessHandle.current().pid(),
                "clientInfo", Json.obj("name", "maghraby-editor", "version", "4.0"),
                "rootUri", rootUri,
                "workspaceFolders", Json.list(Json.obj("uri", rootUri, "name", rootName)),
                "capabilities", Json.obj(
                        "general", Json.obj("positionEncodings", Json.list("utf-16")),
                        "textDocument", Json.obj(
                                "synchronization", Json.obj("dynamicRegistration", false, "willSave", false,
                                        "willSaveWaitUntil", false, "didSave", false),
                                "completion", Json.obj(
                                        "dynamicRegistration", false,
                                        "completionItem", Json.obj(
                                                "snippetSupport", false,
                                                "documentationFormat", Json.list("plaintext")),
                                        "contextSupport", false),
                                "publishDiagnostics", Json.obj("relatedInformation", false)),
                        "workspace", Json.obj("workspaceFolders", true, "configuration", false)));

        request("initialize", params).whenComplete((result, error) -> {
            if (error != null) {
                ready.completeExceptionally(error);
                return;
            }
            Map<String, Object> res = Json.asMap(result);
            Map<String, Object> caps = res == null ? null : Json.asMap(res.get("capabilities"));
            serverCapabilities = caps;
            syncKind = readSyncKind(caps);
            sendNotification("initialized", Json.obj());
            ready.complete(null);
        });
    }

    private static int readSyncKind(Map<String, Object> caps) {
        if (caps == null) return 1; // unknown: full text is always accepted
        Object sync = caps.get("textDocumentSync");
        if (sync instanceof Number) return ((Number) sync).intValue();
        Map<String, Object> m = Json.asMap(sync);
        if (m != null && m.get("change") instanceof Number) return ((Number) m.get("change")).intValue();
        return 1;
    }

    // ------------------------------------------------------------------
    // Transport
    // ------------------------------------------------------------------

    private void send(Object message) throws IOException {
        byte[] body = Json.write(message).getBytes(StandardCharsets.UTF_8);
        byte[] header = ("Content-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
        synchronized (writeLock) {
            toServer.write(header);
            toServer.write(body);
            toServer.flush();
        }
    }

    private void readLoop() {
        String reason = "server closed its output";
        try {
            while (true) {
                String msg = readMessage();
                if (msg == null) break;
                try {
                    dispatch(Json.parse(msg));
                } catch (RuntimeException e) {
                    // One malformed or unexpected message must not kill the connection.
                }
            }
        } catch (IOException e) {
            reason = e.getMessage();
        }
        boolean wasClosedByUs = closed;
        closed = true;
        failAll(new IOException("LSP server exited"));
        ready.completeExceptionally(new IOException("LSP server exited"));
        if (!wasClosedByUs && listener != null) listener.onExit(reason);
    }

    private String readMessage() throws IOException {
        int contentLength = -1;
        while (true) {
            String line = readHeaderLine();
            if (line == null) return null;
            if (line.isEmpty()) break;
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase("Content-Length")) {
                try {
                    contentLength = Integer.parseInt(line.substring(colon + 1).trim());
                } catch (NumberFormatException e) {
                    throw new IOException("Bad Content-Length: " + line);
                }
            }
        }
        if (contentLength < 0) throw new IOException("Missing Content-Length header");
        byte[] body = new byte[contentLength];
        int off = 0;
        while (off < contentLength) {
            int n = fromServer.read(body, off, contentLength - off);
            if (n < 0) return null;
            off += n;
        }
        return new String(body, StandardCharsets.UTF_8);
    }

    private String readHeaderLine() throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream(48);
        while (true) {
            int b = fromServer.read();
            if (b < 0) return line.size() == 0 ? null : line.toString("US-ASCII");
            if (b == '\n') {
                byte[] raw = line.toByteArray();
                int len = raw.length > 0 && raw[raw.length - 1] == '\r' ? raw.length - 1 : raw.length;
                return new String(raw, 0, len, StandardCharsets.US_ASCII);
            }
            line.write(b);
            if (line.size() > 8192) throw new IOException("Header line too long");
        }
    }

    // ------------------------------------------------------------------
    // Dispatch
    // ------------------------------------------------------------------

    private void dispatch(Object parsed) {
        Map<String, Object> msg = Json.asMap(parsed);
        if (msg == null) return;
        Object id = msg.get("id");
        String method = Json.asString(msg.get("method"));

        if (method != null && id != null) {
            handleServerRequest(id, method, msg.get("params"));
        } else if (method != null) {
            handleNotification(method, msg.get("params"));
        } else if (id instanceof Number) {
            CompletableFuture<Object> f = pending.remove(((Number) id).intValue());
            if (f == null) return;
            Map<String, Object> err = Json.asMap(msg.get("error"));
            if (err != null) {
                f.completeExceptionally(new ResponseException(Json.asInt(err.get("code"), -1),
                        String.valueOf(err.get("message"))));
            } else {
                f.complete(msg.get("result"));
            }
        }
    }

    /** Servers also send requests; ignoring them can make a server wait forever, so always answer. */
    private void handleServerRequest(Object id, String method, Object params) {
        Object result = null;
        Map<String, Object> error = null;
        switch (method) {
            case "client/registerCapability":
            case "client/unregisterCapability":
            case "window/workDoneProgress/create":
            case "window/showMessageRequest":
                break; // null result
            case "workspace/configuration": {
                Map<String, Object> p = Json.asMap(params);
                List<Object> items = p == null ? null : Json.asList(p.get("items"));
                List<Object> nulls = new ArrayList<>();
                if (items != null) for (int i = 0; i < items.size(); i++) nulls.add(null);
                result = nulls;
                break;
            }
            case "workspace/workspaceFolders":
                result = Json.list(Json.obj("uri", rootUri,
                        "name", rootDir.getFileName() == null ? "workspace" : rootDir.getFileName().toString()));
                break;
            default:
                error = Json.obj("code", -32601, "message", "Method not found: " + method);
        }
        try {
            if (error != null) send(Json.obj("jsonrpc", "2.0", "id", id, "error", error));
            else send(Json.obj("jsonrpc", "2.0", "id", id, "result", result));
        } catch (IOException ignored) { }
    }

    private void handleNotification(String method, Object params) {
        if (!"textDocument/publishDiagnostics".equals(method) || listener == null) return;
        Map<String, Object> p = Json.asMap(params);
        if (p == null) return;
        String uri = Json.asString(p.get("uri"));
        List<Object> raw = Json.asList(p.get("diagnostics"));
        if (uri == null || raw == null) return;

        List<Diagnostic> out = new ArrayList<>(raw.size());
        for (Object o : raw) {
            Map<String, Object> d = Json.asMap(o);
            if (d == null) continue;
            Map<String, Object> range = Json.asMap(d.get("range"));
            if (range == null) continue;
            Map<String, Object> s = Json.asMap(range.get("start"));
            Map<String, Object> e = Json.asMap(range.get("end"));
            if (s == null || e == null) continue;
            out.add(new Diagnostic(
                    Json.asInt(s.get("line"), 0), Json.asInt(s.get("character"), 0),
                    Json.asInt(e.get("line"), 0), Json.asInt(e.get("character"), 0),
                    Json.asInt(d.get("severity"), 1),
                    String.valueOf(d.get("message")),
                    Json.asString(d.get("source"))));
        }
        listener.onDiagnostics(uri, out);
    }

    private void failAll(Throwable t) {
        for (Integer id : new ArrayList<>(pending.keySet())) {
            CompletableFuture<Object> f = pending.remove(id);
            if (f != null) f.completeExceptionally(t);
        }
    }
}