package org.maghraby.core;

import org.maghraby.lsp.LspDocumentSync;
import org.maghraby.ui.Split;
import org.maghraby.syntax.HighlighterRegistry;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Shared editor state: the open documents, cursors, mode, status line and suggestion popup state.
 * Everything is static and package-private; the other classes pull it in with a static import.
 */
public final class State {
    private State() {}

    public static volatile boolean running = true;
    public static volatile boolean needsRedraw = true;
    public static final AtomicBoolean forceClear = new AtomicBoolean(true);
    public static final HighlighterRegistry highlighterRegistry = new HighlighterRegistry();
    public static Doc[] docs = new Doc[2]; // docs[pane]; docs[1] is only meaningful while split

    static { docs[0] = new Doc(new TextEditor(), "Untitled.txt"); }

    /** The document of the focused pane. */
    public static Doc D() { return docs[focus]; }

    public static TextEditor ed() { return docs[focus].editor; }

    // 2D Cursor Coordinates (Character coordinates inside the buffer)
    public static volatile int cursorRow = 0;

    public static volatile int cursorCol = 0;
    public static int goalCol = 0;          // Remembered column for Up/Down movement (Input thread only)
    public static boolean keepGoal = false; // Flag set by vertical moves (Input thread only)
    public static final String DEFAULT_STATUS =
            "i:insert  ::command  Tab:switch pane  u:undo  r:redo  F5:run  q:quit  (:help)";
    public static final String HELP =
            ":w [file]  :wq  :q  :q!  :run [args]  :compile  :lsp [stop]  :vsplit  :split  :only  :set rnu|nornu  |  Tab/Ctrl+W switch pane  F5 run  Ctrl+S save  Ctrl+Q force-quit";
    public static volatile String statusMessage = DEFAULT_STATUS;
    public static volatile Mode currentMode = Mode.NORMAL;
    public static final StringBuffer commandInput = new StringBuffer();

    // --- Relative line numbers, git branch (status line) ---
    public static volatile boolean relativeNumbers = true;

    public static volatile String gitBranch = "";
    public static volatile Split split = Split.NONE;
    public static volatile int focus = 0;                 // which pane owns the cursor statics (0 or 1)
    public static final int[] savedRow = new int[2];      // cursor of the pane that is NOT focused
    public static final int[] savedCol = new int[2];
    public static final int[] savedGoal = new int[2];
    public static final int[] paneTop = new int[2];       // Render thread only
    public static final int[] paneLeft = new int[2];      // Render thread only
    public static final int TAB_WIDTH = 4;

    // --- Autocomplete State ---
    public static volatile List<LspDocumentSync.Completion> activeSuggestions = null;

    public static volatile int suggestionIndex = 0;
    public static volatile boolean showingSuggestions = false;

    public static void clampCursor() {
        int maxRow = Math.max(0, ed().getLineCount() - 1);
        cursorRow = Math.max(0, Math.min(cursorRow, maxRow));
        cursorCol = Math.max(0, Math.min(cursorCol, ed().getLineLength(cursorRow)));
    }
}
