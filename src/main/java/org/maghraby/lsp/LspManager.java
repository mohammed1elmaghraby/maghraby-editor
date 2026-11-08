package org.maghraby.lsp;

import org.maghraby.core.Doc;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.maghraby.core.State.*;

/**
 * Starting and stopping the language server of a document.
 */
public final class LspManager {
    private LspManager() {}

    /** (Re)starts the language server for a document. Everything slow happens on background threads. */
    public static void startLsp(Doc d) {
        d.lspStoppedByUser = false;
        stopLsp(d, false);
        LspDocumentSync sync = LspDocumentSync.tryStart(d.filename, d.editor, new LspClient.Listener() {
            @Override
            public void onDiagnostics(String uri, List<LspClient.Diagnostic> list) { // reader thread
                LspDocumentSync s = d.lsp;
                if (s != null && s.isCurrent(uri)) {
                    d.diagnostics = list;
                    needsRedraw = true;
                }
            }

            @Override
            public void onExit(String reason) { // reader thread
                LspDocumentSync s = d.lsp;
                d.lsp = null;
                d.diagnostics = new ArrayList<>();
                statusMessage = "LSP server exited. :lsp to restart";
                needsRedraw = true;
                if (s != null) {
                    Thread t = new Thread(s::close, "LSP-Cleanup");
                    t.setDaemon(true);
                    t.start();
                }
            }
        });
        d.lsp = sync;
        if (sync != null) {
            sync.open(Paths.get(d.filename.trim()).toAbsolutePath().normalize());
        } else {
            statusMessage = "No language server found for '" + d.filename + "' (is it installed and on PATH?)";
        }
    }

    /**
     * Shutting a server down can take over a second, so unless we are quitting it happens on a
     * background thread and the editor never stalls.
     */
    public static void stopLsp(Doc d, boolean blocking) {
        LspDocumentSync s = d.lsp;
        d.lsp = null;
        d.diagnostics = new ArrayList<>();
        if (s == null) return;
        if (blocking) {
            s.close();
        } else {
            Thread t = new Thread(s::close, "LSP-Cleanup");
            t.setDaemon(true);
            t.start();
        }
    }

    /** Language id of a document ("go", "python", ...), or null when the language is not supported. */
    private static String languageOf(Doc d) {
        return d == null ? null : LspServers.languageId(d.filename);
    }

    /**
     * Keeps only the language server of the ACTIVE (focused) language running. Call it whenever the focus,
     * a pane's document or a file name changes. The focused document gets its server (unless the user
     * stopped it with ":lsp stop"); the document in the other pane keeps its server only when it is the
     * same language, otherwise that server is shut down.
     */
    public static void syncActiveLsp() {
        Doc active = D();
        if (active == null) return; // a pane without a document
        Doc other = split == org.maghraby.ui.Split.NONE ? null : docs[1 - focus];
        if (other != null && other != active && other.lsp != null) {
            String a = languageOf(active), o = languageOf(other);
            if (o == null || !o.equals(a)) stopLsp(other, false);
        }
        if (active.lsp == null && !active.lspStoppedByUser && languageOf(active) != null) {
            startLsp(active);
        }
    }

    public static void stopAllLsp() {
        Doc a = docs[0], b = docs[1];
        if (a != null) stopLsp(a, true);
        if (b != null && b != a) stopLsp(b, true);
    }
}