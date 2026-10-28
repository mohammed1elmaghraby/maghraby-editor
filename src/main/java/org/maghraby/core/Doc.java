package org.maghraby.core;

import org.maghraby.lsp.LspClient;
import org.maghraby.lsp.LspDocumentSync;
import org.maghraby.syntax.HighlightStateCache;

import java.util.ArrayList;
import java.util.List;

/** One open file: it's buffer, name, language server and diagnostics. Two panes may share one Doc. */
public final class Doc {
    public TextEditor editor;
    public volatile String filename;
    public volatile LspDocumentSync lsp = null;
    public volatile List<LspClient.Diagnostic> diagnostics = new ArrayList<>(); // replaced as a whole, never mutated
    public final HighlightStateCache cache = new HighlightStateCache();         // Render thread only

    public Doc(TextEditor editor, String filename) {
        this.editor = editor;
        this.filename = filename;
    }
}
