package org.maghraby;

import org.maghraby.syntax.HighlightState;
import org.maghraby.syntax.HighlightStateCache;
import org.maghraby.syntax.HighlighterRegistry;
import org.maghraby.syntax.SyntaxHighlighter;
import org.maghraby.syntax.SyntaxHighlighter.HighlightResult;

import java.io.BufferedOutputStream;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Terminal text editor (vim-like modes) with syntax highlighting and :run / :compile capabilities.
 * Requires a real terminal supporting ANSI escapes and `stty` (Linux, macOS, WSL, Git-Bash...).
 */
public class Main {

    // ------------------------------------------------------------------
    // Shared State & Configurations
    // ------------------------------------------------------------------

    private static volatile boolean running = true;
    private static volatile boolean needsRedraw = true;
    private static final AtomicBoolean forceClear = new AtomicBoolean(true);

    private static final HighlighterRegistry highlighterRegistry = new HighlighterRegistry();

    /** One open file: its buffer, name, language server and diagnostics. Two panes may share one Doc. */
    private static final class Doc {
        final TextEditor editor;
        volatile String filename;
        volatile LspDocumentSync lsp = null;
        volatile List<LspClient.Diagnostic> diagnostics = new ArrayList<>(); // replaced as a whole, never mutated
        final HighlightStateCache cache = new HighlightStateCache();         // Render thread only

        Doc(TextEditor editor, String filename) {
            this.editor = editor;
            this.filename = filename;
        }
    }

    private static final Doc[] docs = new Doc[2]; // docs[pane]; docs[1] is only meaningful while split
    static { docs[0] = new Doc(new TextEditor(), "Untitled.txt"); }

    /** The document of the focused pane. */
    private static Doc D() { return docs[focus]; }

    private static TextEditor ed() { return docs[focus].editor; }

    // --- LSP (optional: null when no language server is installed for the current file) ---
    // Diagnostics of the open file. The list is replaced as a whole (never mutated), so the render
    // thread can read it without locking.

    // 2D Cursor Coordinates (Character coordinates inside the buffer)
    private static volatile int cursorRow = 0;
    private static volatile int cursorCol = 0;
    private static int goalCol = 0;          // Remembered column for Up/Down movement (Input thread only)
    private static boolean keepGoal = false; // Flag set by vertical moves (Input thread only)

    private static final String DEFAULT_STATUS =
            "i:insert  ::command  Tab:switch pane  u:undo  r:redo  F5:run  q:quit  (:help)";
    private static final String HELP =
            ":w [file]  :wq  :q  :q!  :run [args]  :compile  :lsp [stop]  :vsplit  :split  :only  :set rnu|nornu  |  Tab/Ctrl+W switch pane  F5 run  Ctrl+S save  Ctrl+Q force-quit";

    private static volatile String statusMessage = DEFAULT_STATUS;
    private static volatile Mode currentMode = Mode.NORMAL;
    private static final StringBuffer commandInput = new StringBuffer();

    // --- Relative line numbers, git branch (status line) ---
    private static volatile boolean relativeNumbers = true;
    private static volatile String gitBranch = "";
    private static int lastClockMinute = -1;    // Render thread only

    // --- Split screen: two panes showing the same buffer, each with its own cursor and scroll ---
    enum Split { NONE, VERTICAL, HORIZONTAL }
    private static volatile Split split = Split.NONE;
    private static volatile int focus = 0;                 // which pane owns the cursor statics (0 or 1)
    private static final int[] savedRow = new int[2];      // cursor of the pane that is NOT focused
    private static final int[] savedCol = new int[2];
    private static final int[] savedGoal = new int[2];
    private static final int[] paneTop = new int[2];       // Render thread only
    private static final int[] paneLeft = new int[2];      // Render thread only

    private static volatile int termRows = 24;
    private static volatile int termCols = 80;

    private static final int TAB_WIDTH = 4;

    // Concurrency lock: Rendering is paused while an external program owns the terminal
    private static final Object RENDER_LOCK = new Object();
    private static volatile boolean renderPaused = false;

    private static String originalStty = null;
    private static final AtomicBoolean terminalRestored = new AtomicBoolean(false);

    private static final PrintStream out = createOut();

    // ------------------------------------------------------------------
    // ANSI Escape Codes
    // ------------------------------------------------------------------

    private static final String ESC = "\033";
    private static final String ANSI_HIDE_CURSOR = ESC + "[?25l";
    private static final String ANSI_SHOW_CURSOR = ESC + "[?25h";
    private static final String ANSI_RESET = ESC + "[0m";
    private static final String ANSI_CLEAR_SCREEN = ESC + "[2J";
    private static final String ANSI_CLEAR_LINE = ESC + "[2K";
    private static final String ANSI_ENTER_ALT_SCREEN = ESC + "[?1049h";
    private static final String ANSI_LEAVE_ALT_SCREEN = ESC + "[?1049l";
    private static final String ANSI_BG_BLUE_FG_WHITE = ESC + "[44m" + ESC + "[37m";
    private static final String ANSI_BG_WHITE_FG_BLACK = ESC + "[47m" + ESC + "[30m";

    // --- Autocomplete State ---
    private static volatile List<LspDocumentSync.Completion> activeSuggestions = null;
    private static volatile int suggestionIndex = 0;
    private static volatile boolean showingSuggestions = false;

    /** Called from the LSP thread once completions for the current cursor position arrive. */
    private static void showSuggestionsUi(List<LspDocumentSync.Completion> suggestions) {
        if (suggestions == null || suggestions.isEmpty()) {
            closeSuggestions(false);
            return;
        }
        activeSuggestions = suggestions;
        suggestionIndex = 0;
        showingSuggestions = true;
        needsRedraw = true;
    }

    private static void closeSuggestions() {
        closeSuggestions(true);
    }

    /** @param cancelPending also drop a request still in flight, so it cannot pop up later. */
    private static void closeSuggestions(boolean cancelPending) {
        if (cancelPending) {
            LspDocumentSync s = D().lsp;
            if (s != null) s.cancelSuggestions();
        }
        showingSuggestions = false;
        activeSuggestions = null;
        needsRedraw = true;
    }

    // --- Colour helpers (256-colour palette) ---
    private static final String BOLD = ESC + "[1m";
    private static final String BOLD_OFF = ESC + "[22m";
    private static final String FG_DEFAULT = ESC + "[39m";
    private static volatile boolean statusFadePending = false; // a status message is about to fade
    private static final String[] SPINNER = {
            "⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"
    };
    private static final long STATUS_TTL_MS = 5000; // a status message fades back to the mode hint

    private static String fg(int n) { return ESC + "[38;5;" + n + "m"; }
    private static String bg(int n) { return ESC + "[48;5;" + n + "m"; }

    // Render thread only: when the current status message first appeared.
    private static String lastStatusSeen = "";
    private static long statusSince = System.currentTimeMillis();

    enum Mode { NORMAL, INSERT, COMMAND }

    private static PrintStream createOut() {
        try {
            return new PrintStream(new BufferedOutputStream(new FileOutputStream(FileDescriptor.out), 1 << 16),
                    false, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return System.out;
        }
    }

    // ------------------------------------------------------------------
    // Entry Point
    // ------------------------------------------------------------------

    public static void main(String[] args) {
        originalStty = stty("-g");
        if (originalStty == null) {
            System.err.println("Error: This editor requires a real terminal capable of 'stty' configuration.");
            System.exit(1);
            return;
        }

        if (args.length > 0 && !args[0].trim().isEmpty()) {
            docs[0].filename = args[0].trim();
            statusMessage = docs[0].editor.loadFromFile(docs[0].filename);
            startLsp(docs[0]); // background: never delays startup
        }
        refreshGitBranch();

        Runtime.getRuntime().addShutdownHook(new Thread(Main::restoreTerminal, "Terminal-Restore"));

        Thread renderThread = null;
        try {
            updateTerminalSize();
            enterEditorScreen();

            renderThread = new Thread(Main::renderLoop, "Render-Thread");
            renderThread.start();

            Thread resizeThread = new Thread(Main::resizeWatcher, "Resize-Watcher");
            resizeThread.setDaemon(true);
            resizeThread.start();

            inputLoop();
        } catch (Throwable t) {
            running = false;
            restoreTerminal();
            t.printStackTrace();
        } finally {
            running = false;
            if (renderThread != null) {
                try { renderThread.join(1000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            }
            restoreTerminal(); // give the screen back first, the language server shutdown may take a moment
            stopAllLsp();
        }
    }

    // ------------------------------------------------------------------
    // Input Thread & Processing
    // ------------------------------------------------------------------

    private static void inputLoop() throws IOException {
        InputStream in = System.in;
        boolean lastWasCR = false;

        while (running) {
            int b = in.read(); // Blocking read: no busy polling, EOF ends the editor
            if (b == -1) break;

            boolean isCR = false;
            keepGoal = false;
            try {
                if (b == 17) {                                   // Ctrl+Q: Force quit
                    running = false;
                    break;
                } else if (b == 19) {                            // Ctrl+S: Save
                    saveCommand("");
                } else if (b == 23) {                            // Ctrl+W: switch split pane
                    switchPane();
                } else if (b == 27) {
                    handleEscapeSequence(in);
                } else if (b == 127 || b == 8) {
                    handleBackspace();
                } else if (b == 13) {
                    isCR = true;
                    handleEnter();
                } else if (b == 10) {
                    if (!lastWasCR) handleEnter();               // Swallow LF of pasted CR LF pair
                } else if (b == 9) {
                    handleTab();
                } else if (b >= 32 && b < 127) {
                    handleText(String.valueOf((char) b));
                } else if (b >= 0xC2 && b <= 0xF4) {
                    String s = readUtf8(in, b);
                    if (s != null) handleText(s);
                }
            } catch (RuntimeException e) {
                statusMessage = "Internal error: " + e;          // Prevent crashing over one bad key
            }
            lastWasCR = isCR;

            clampCursor();
            if (!keepGoal) goalCol = cursorCol;
            needsRedraw = true;
        }
    }

    /** Reads one byte, waiting at most `ms` milliseconds. Returns -1 on timeout. */
    private static int readTimeout(InputStream in, int ms) throws IOException {
        long end = System.nanoTime() + ms * 1_000_000L;
        while (in.available() <= 0) {
            if (System.nanoTime() >= end) return -1;
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return -1;
            }
        }
        return in.read();
    }

    private static String readUtf8(InputStream in, int lead) throws IOException {
        int extra = lead >= 0xF0 ? 3 : lead >= 0xE0 ? 2 : 1;
        byte[] buf = new byte[extra + 1];
        buf[0] = (byte) lead;
        for (int k = 1; k <= extra; k++) {
            int c = readTimeout(in, 50);
            if (c == -1 || (c & 0xC0) != 0x80) return null;
            buf[k] = (byte) c;
        }
        String s = new String(buf, StandardCharsets.UTF_8);
        return s.indexOf('\uFFFD') >= 0 ? null : s;
    }

    /** Distinguishes a bare Esc key from arrow/Home/End/Delete/F-key sequences. */
    private static void handleEscapeSequence(InputStream in) throws IOException {
        int next = readTimeout(in, 30);
        if (next == -1) { // Plain Esc key
            handleEscape();
            return;
        }
        if (next == 'O') { // SS3 keys (F1-F4)
            int c = readTimeout(in, 30);
            if (c == 'A') handleArrow(-1, 0);
            else if (c == 'B') handleArrow(1, 0);
            else if (c == 'C') handleArrow(0, 1);
            else if (c == 'D') handleArrow(0, -1);
            else if (c == 'H') handleHome();
            else if (c == 'F') handleEnd();
            return;
        }
        if (next != '[') { // Alt+key: treat as Esc
            handleEscape();
            return;
        }

        // CSI sequences
        StringBuilder params = new StringBuilder();
        int fin = -1;
        for (int n = 0; n < 16; n++) {
            int c = readTimeout(in, 30);
            if (c == -1) return;
            if (c >= 0x40 && c <= 0x7E) { fin = c; break; }
            params.append((char) c);
        }
        if (fin == -1) return;

        String p = params.toString();
        int semi = p.indexOf(';');
        String base = semi >= 0 ? p.substring(0, semi) : p; // Ignore modifiers

        switch (fin) {
            case 'A': handleArrow(-1, 0); break;
            case 'B': handleArrow(1, 0); break;
            case 'C': handleArrow(0, 1); break;
            case 'D': handleArrow(0, -1); break;
            case 'H': handleHome(); break;
            case 'F': handleEnd(); break;
            case '~':
                switch (base) {
                    case "1": case "7": handleHome(); break;
                    case "4": case "8": handleEnd(); break;
                    case "3": handleDeleteKey(); break;
                    case "5": handlePage(-1); break;
                    case "6": handlePage(1); break;
                    case "15": runCommand(true, ""); break;       // F5
                }
                break;
        }
    }

    // ------------------------------------------------------------------
    // Key Handlers
    // ------------------------------------------------------------------

    private static void handleText(String s) {
        if (currentMode == Mode.INSERT) {
            int[] pos = ed().insertAt(cursorRow, cursorCol, s);
            cursorRow = pos[0];
            cursorCol = pos[1];
            triggerCompletion();
        } else if (currentMode == Mode.COMMAND) {
            commandInput.append(s);
        } else if (s.length() == 1) {
            char c = s.charAt(0);
            if (c == 'q') {
                tryQuit();
            } else if (c == 'i') {
                currentMode = Mode.INSERT;
                statusMessage = "-- INSERT MODE -- (Esc: back to Normal)";
            } else if (c == ':') {
                currentMode = Mode.COMMAND;
                commandInput.setLength(0);
                statusMessage = "Command mode: " + HELP;
            } else if (c == 'u') {
                int[] pos = {cursorRow, cursorCol};
                statusMessage = ed().undo(pos);
                cursorRow = pos[0];
                cursorCol = pos[1];
            } else if (c == 'r') {
                int[] pos = {cursorRow, cursorCol};
                statusMessage = ed().redo(pos);
                cursorRow = pos[0];
                cursorCol = pos[1];
            }
        }
    }

    // ------------------------------------------------------------------
    // Completion helpers (input thread)
    // ------------------------------------------------------------------

    /** The identifier characters immediately before the cursor ("pri" in "System.out.pri|"). */
    private static String identifierPrefix() {
        String line = ed().getLine(cursorRow);
        int end = Math.min(cursorCol, line.length());
        int start = end;
        while (start > 0 && Character.isJavaIdentifierPart(line.charAt(start - 1))) start--;
        return line.substring(start, end);
    }

    /** True when the character before the cursor is a member-access trigger: '.', '->' or '::'. */
    private static boolean afterTriggerChar() {
        String line = ed().getLine(cursorRow);
        int end = Math.min(cursorCol, line.length());
        if (end == 0) return false;
        char c = line.charAt(end - 1);
        if (c == '.') return true;
        if (end >= 2) {
            char p = line.charAt(end - 2);
            return (c == '>' && p == '-') || (c == ':' && p == ':');
        }
        return false;
    }

    /** Asks the language server for completions, but only when it makes sense at the cursor. */
    private static void triggerCompletion() {
        LspDocumentSync s = D().lsp;
        String prefix = identifierPrefix();
        boolean useServer = lspUsable(s);
        if (prefix.isEmpty() && !afterTriggerChar()) {
            if (showingSuggestions) closeSuggestions(); else if (s != null) s.cancelSuggestions();
            return;
        }
        if (useServer) {
            s.requestSuggestions(cursorRow, cursorCol, prefix, Main::showSuggestionsUi);
        } else if (!prefix.isEmpty()) {
            // No language server (not installed, still starting, or crashed): complete from the words
            // already in the file plus the language's keywords.
            showSuggestionsUi(localSuggestions(prefix));
        } else if (showingSuggestions) {
            closeSuggestions();
        }
    }

    /** True when the language server is running and has finished its handshake. */
    private static boolean lspUsable(LspDocumentSync s) {
        if (s == null) return false;
        LspClient c = s.client();
        return c.isAlive() && c.ready().isDone() && !c.ready().isCompletedExceptionally();
    }

    private static final String[] JAVA_WORDS = {
            "abstract", "boolean", "break", "byte", "case", "catch", "char", "class", "continue", "default",
            "do", "double", "else", "enum", "extends", "final", "finally", "float", "for", "if", "implements",
            "import", "instanceof", "int", "interface", "long", "new", "null", "package", "private",
            "protected", "public", "return", "short", "static", "super", "switch", "this", "throw", "throws",
            "try", "void", "volatile", "while", "String", "System", "List", "Map", "ArrayList", "HashMap"};
    private static final String[] C_WORDS = {
            "auto", "break", "case", "char", "const", "continue", "default", "do", "double", "else", "enum",
            "extern", "float", "for", "goto", "if", "include", "int", "long", "namespace", "return", "short",
            "signed", "sizeof", "static", "struct", "switch", "typedef", "union", "unsigned", "void",
            "volatile", "while", "printf", "scanf", "malloc", "free", "std", "string", "vector", "cout", "cin"};
    private static final String[] PY_WORDS = {
            "and", "as", "assert", "break", "class", "continue", "def", "del", "elif", "else", "except",
            "finally", "for", "from", "global", "if", "import", "in", "is", "lambda", "None", "not", "or",
            "pass", "raise", "return", "self", "True", "False", "try", "while", "with", "yield", "print",
            "range", "len", "str", "int", "list", "dict"};

    private static String[] keywordsFor(String filename) {
        CodeRunner.Language l = CodeRunner.detect(filename);
        if (l == null) return new String[0];
        switch (l) {
            case JAVA: return JAVA_WORDS;
            case C: case CPP: return C_WORDS;
            case PYTHON: return PY_WORDS;
            default: return new String[0];
        }
    }

    /** Word-based completion: identifiers near the cursor and language keywords that start with {@code prefix}. */
    private static List<LspDocumentSync.Completion> localSuggestions(String prefix) {
        String lower = prefix.toLowerCase(java.util.Locale.ROOT);
        java.util.Map<String, Integer> counts = new java.util.HashMap<>();
        java.util.Map<String, Boolean> isKeyword = new java.util.HashMap<>();

        // Scan a window around the cursor so a huge file doesn't make every keystroke slow.
        int from = Math.max(0, cursorRow - 2000);
        int to = Math.min(ed().getLineCount(), cursorRow + 2000);
        for (String line : ed().getLines(from, to)) {
            int n = line.length();
            int i = 0;
            while (i < n) {
                if (Character.isJavaIdentifierStart(line.charAt(i))) {
                    int j = i + 1;
                    while (j < n && Character.isJavaIdentifierPart(line.charAt(j))) j++;
                    String w = line.substring(i, j);
                    if (w.length() > prefix.length() && w.toLowerCase(java.util.Locale.ROOT).startsWith(lower)) {
                        counts.merge(w, 1, Integer::sum);
                    }
                    i = j;
                } else {
                    i++;
                }
            }
        }
        for (String k : keywordsFor(D().filename)) {
            if (k.length() > prefix.length() && k.toLowerCase(java.util.Locale.ROOT).startsWith(lower)) {
                counts.merge(k, 1, Integer::sum);
                isKeyword.put(k, Boolean.TRUE);
            }
        }

        List<String> words = new ArrayList<>(counts.keySet());
        words.sort((a, b) -> {
            boolean ca = a.startsWith(prefix), cb = b.startsWith(prefix); // same case as typed first
            if (ca != cb) return ca ? -1 : 1;
            int d = counts.get(b) - counts.get(a);                         // then the most frequent
            return d != 0 ? d : a.compareTo(b);
        });

        List<LspDocumentSync.Completion> out = new ArrayList<>();
        int replaceFrom = cursorCol - prefix.length();
        for (String w : words) {
            out.add(new LspDocumentSync.Completion(w, isKeyword.containsKey(w) ? "keyword" : "word",
                    w, isKeyword.containsKey(w) ? 14 : 0, Math.max(0, replaceFrom)));
            if (out.size() >= 30) break;
        }
        return out;
    }

    /** Replaces the typed prefix with the highlighted suggestion. */
    private static void acceptSuggestion() {
        List<LspDocumentSync.Completion> list = activeSuggestions;
        if (list == null || list.isEmpty()) return;
        LspDocumentSync.Completion c = list.get(Math.max(0, Math.min(suggestionIndex, list.size() - 1)));
        closeSuggestions();

        int from = Math.min(c.replaceFromCol, cursorCol);
        for (int n = cursorCol - from; n > 0; n--) {
            int[] pos = ed().deleteAt(cursorRow, cursorCol);
            cursorRow = pos[0];
            cursorCol = pos[1];
        }
        int[] pos = ed().insertAt(cursorRow, cursorCol, c.insertText);
        cursorRow = pos[0];
        cursorCol = pos[1];
    }

    private static boolean suggestionsOpen() {
        List<LspDocumentSync.Completion> l = activeSuggestions;
        return showingSuggestions && l != null && !l.isEmpty();
    }

    private static void handleTab() {
        if (currentMode == Mode.NORMAL) { switchPane(); return; }
        if (currentMode != Mode.INSERT) return;
        if (suggestionsOpen()) { // Tab accepts the highlighted suggestion
            acceptSuggestion();
            return;
        }
        int spaces = TAB_WIDTH - (cursorCol % TAB_WIDTH);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < spaces; i++) sb.append(' ');
        handleText(sb.toString());
    }

    private static void handleBackspace() {
        if (currentMode == Mode.INSERT) {
            int[] pos = ed().deleteAt(cursorRow, cursorCol);
            cursorRow = pos[0];
            cursorCol = pos[1];
            if (showingSuggestions) triggerCompletion(); // narrows, or closes when nothing is left to complete
        } else if (currentMode == Mode.COMMAND) {
            if (commandInput.length() > 0) {
                commandInput.deleteCharAt(commandInput.length() - 1);
            } else {
                currentMode = Mode.NORMAL; // Backspace on empty line cancels command mode
                statusMessage = "NORMAL MODE";
            }
        }
    }

    private static void handleDeleteKey() {
        if (currentMode != Mode.INSERT) return;
        if (showingSuggestions) closeSuggestions();
        int[] pos = ed().deleteForward(cursorRow, cursorCol);
        cursorRow = pos[0];
        cursorCol = pos[1];
    }

    private static void handleEnter() {
        if (currentMode == Mode.INSERT) {
            if (showingSuggestions) closeSuggestions(); else if (D().lsp != null) D().lsp.cancelSuggestions();

            // Auto-indent: carry over the current line's indentation, one level deeper after an opener.
            String before = ed().getLine(cursorRow);
            before = before.substring(0, Math.min(cursorCol, before.length()));
            int ws = 0;
            while (ws < before.length() && (before.charAt(ws) == ' ' || before.charAt(ws) == '\t')) ws++;
            StringBuilder insert = new StringBuilder("\n").append(before, 0, ws);
            String trimmed = before.stripTrailing();
            if (!trimmed.isEmpty()) {
                char last = trimmed.charAt(trimmed.length() - 1);
                if (last == '{' || last == '(' || last == '[' || last == ':') {
                    for (int i = 0; i < TAB_WIDTH; i++) insert.append(' ');
                }
            }
            int[] pos = ed().insertAt(cursorRow, cursorCol, insert.toString());
            cursorRow = pos[0];
            cursorCol = pos[1];
        } else if (currentMode == Mode.COMMAND) {
            String cmd = commandInput.toString().trim();
            commandInput.setLength(0);
            currentMode = Mode.NORMAL;
            executeCommand(cmd);
        }
    }

    private static void handleEscape() {
        if (showingSuggestions) {
            closeSuggestions();
            return;
        }
        if (currentMode != Mode.NORMAL) {
            currentMode = Mode.NORMAL;
            commandInput.setLength(0);
            statusMessage = "NORMAL MODE";
        }
    }

    private static void handleArrow(int rowDelta, int colDelta) {
        if (suggestionsOpen()) {
            List<LspDocumentSync.Completion> list = activeSuggestions;
            if (list != null && rowDelta != 0) { // Up/Down move the selection
                suggestionIndex = (suggestionIndex + rowDelta + list.size()) % list.size();
                return;
            }
            closeSuggestions(); // Left/Right leave the word
        }
        if (currentMode == Mode.COMMAND) return;

        if (colDelta < 0) {
            if (cursorCol > 0) {
                cursorCol--;
            } else if (cursorRow > 0) {
                cursorRow--;
                cursorCol = ed().getLineLength(cursorRow);
            }
        } else if (colDelta > 0) {
            if (cursorCol < ed().getLineLength(cursorRow)) {
                cursorCol++;
            } else if (cursorRow < ed().getLineCount() - 1) {
                cursorRow++;
                cursorCol = 0;
            }
        }

        if (rowDelta != 0) {
            cursorRow += rowDelta;
            cursorCol = goalCol;
            keepGoal = true;
        }
    }

    private static void handleHome() {
        if (showingSuggestions) closeSuggestions();
        if (currentMode == Mode.NORMAL || currentMode == Mode.INSERT) cursorCol = 0;
    }

    private static void handleEnd() {
        if (showingSuggestions) closeSuggestions();
        if (currentMode == Mode.NORMAL || currentMode == Mode.INSERT) cursorCol = ed().getLineLength(cursorRow);
    }

    private static void handlePage(int direction) {
        if (currentMode == Mode.COMMAND) return;
        if (showingSuggestions) closeSuggestions();
        int step = Math.max(1, termRows - 4);
        cursorRow += direction * step;
        cursorCol = goalCol;
        keepGoal = true;
    }

    private static void clampCursor() {
        int maxRow = Math.max(0, ed().getLineCount() - 1);
        cursorRow = Math.max(0, Math.min(cursorRow, maxRow));
        cursorCol = Math.max(0, Math.min(cursorCol, ed().getLineLength(cursorRow)));
    }

    // ------------------------------------------------------------------
    // Editor Commands Execution
    // ------------------------------------------------------------------

    private static void tryQuit() {
        if (split != Split.NONE) { // like vim: quitting a split only closes that pane
            if (closePane(focus, false)) statusMessage = "Pane closed.";
            return;
        }
        if (ed().isDirty()) {
            statusMessage = "Unsaved changes! :w to save, :wq to save+quit, :q! (or Ctrl+Q) to discard.";
        } else {
            running = false;
        }
    }

    private static boolean saveCommand(String arg) {
        String target = arg.isEmpty() ? D().filename : arg;
        String msg = ed().saveToFile(target);
        statusMessage = msg;
        boolean ok = msg.startsWith("Saved");
        if (ok) {
            boolean renamed = !target.equals(D().filename);
            D().filename = target;
            refreshGitBranch();
            if (renamed) startLsp(D()); // a new name can mean a different language (Untitled.txt -> a.go)
        }
        return ok;
    }

    private static void executeCommand(String cmdLine) {
        if (cmdLine.isEmpty()) {
            statusMessage = "NORMAL MODE";
            return;
        }
        String[] parts = cmdLine.split("\\s+", 2);
        String name = parts[0];
        String arg = parts.length > 1 ? parts[1].trim() : "";

        switch (name) {
            case "q": case "quit": case "exit": tryQuit(); break;
            case "q!": case "quit!":
                if (split != Split.NONE) closePane(focus, true);
                else running = false;
                break;
            case "w": case "write": saveCommand(arg); break;
            case "wq": case "x":
                if (saveCommand(arg)) {
                    if (split != Split.NONE) closePane(focus, true);
                    else running = false;
                }
                break;
            case "run": runCommand(true, arg); break;
            case "compile": case "build": runCommand(false, arg); break;
            case "lsp":
                if (arg.equals("stop")) {
                    stopLsp(D(), false);
                    statusMessage = "LSP stopped.";
                } else {
                    startLsp(D());
                }
                break;
            case "split": case "sp": openSplit(Split.HORIZONTAL, arg); break;
            case "vsplit": case "vs": case "vsp": openSplit(Split.VERTICAL, arg); break;
            case "only": case "on":
                if (split == Split.NONE) statusMessage = "Only one pane.";
                else if (closePane(1 - focus, false)) statusMessage = "Single pane.";
                break;
            case "set":
                if (arg.equals("rnu") || arg.equals("relativenumber")) {
                    relativeNumbers = true;
                    statusMessage = "Relative line numbers on.";
                } else if (arg.equals("nornu") || arg.equals("norelativenumber")) {
                    relativeNumbers = false;
                    statusMessage = "Relative line numbers off.";
                } else {
                    statusMessage = "Unknown option: " + arg + "   (rnu, nornu)";
                }
                break;
            case "help": case "h": statusMessage = HELP; break;
            default: statusMessage = "Unknown command: " + cmdLine + "   (try :help)";
        }
    }

    // ------------------------------------------------------------------
    // Language Server (LSP)
    // ------------------------------------------------------------------

    /** (Re)starts the language server for a document. Everything slow happens on background threads. */
    private static void startLsp(Doc d) {
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
    private static void stopLsp(Doc d, boolean blocking) {
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

    private static void stopAllLsp() {
        Doc a = docs[0], b = docs[1];
        if (a != null) stopLsp(a, true);
        if (b != null && b != a) stopLsp(b, true);
    }

    /** Right-hand header badge for the language server: {coloured, plain} (plain is used to measure width). */
    private static String[] lspBadge() {
        LspDocumentSync s = D().lsp;
        if (s == null) return new String[]{"", ""};
        if (!s.client().ready().isDone()) {
            String frame = SPINNER[(int) ((System.currentTimeMillis() / 100) % SPINNER.length)];
            String plain = frame + " LSP starting ";
            return new String[]{fg(221) + plain + FG_DEFAULT, plain};
        }
        int errors = 0, warnings = 0;
        for (LspClient.Diagnostic d : D().diagnostics) {
            if (d.severity == 1) errors++;
            else if (d.severity == 2) warnings++;
        }
        if (errors == 0 && warnings == 0) {
            String plain = "✔ LSP ok ";
            return new String[]{fg(78) + plain + FG_DEFAULT, plain};
        }
        String e = "● " + errors + " ";
        String w = "▲ " + warnings + " ";
        return new String[]{
                fg(203) + e + fg(214) + w + FG_DEFAULT,
                e + w};
    }

    /** The most severe diagnostic touching `row`, or null. */
    private static LspClient.Diagnostic bestDiagnosticAt(List<LspClient.Diagnostic> diags, int row) {
        LspClient.Diagnostic best = null;
        for (LspClient.Diagnostic d : diags) {
            // An end position at column 0 of the next line is exclusive: it doesn't touch that line.
            int last = (d.endCol == 0 && d.endLine > d.line) ? d.endLine - 1 : d.endLine;
            if (row < d.line || row > last) continue;
            if (best == null || d.severity < best.severity) best = d;
        }
        return best;
    }

    private static int severityColor(int severity) {
        return severity == 1 ? 203 : severity == 2 ? 214 : severity == 3 ? 81 : 245;
    }

    /** Saves, builds, and optionally runs the file while handing over terminal access. */
    private static void runCommand(boolean run, String argLine) {
        String file = D().filename;
        if (CodeRunner.detect(file) == null) {
            statusMessage = "Can't " + (run ? "run" : "compile") + " '" + file + "'. Supported: "
                    + CodeRunner.supportedExtensions() + "  (rename with :w name.ext)";
            return;
        }
        String saved = ed().saveToFile(file);
        if (!saved.startsWith("Saved")) {
            statusMessage = saved;
            return;
        }

        CodeRunner.Plan plan;
        try {
            plan = CodeRunner.plan(file, run, run ? splitArgs(argLine) : new ArrayList<String>());
        } catch (CodeRunner.RunnerException e) {
            statusMessage = e.getMessage();
            return;
        }

        String summary;
        suspendTerminal();
        try {
            summary = CodeRunner.execute(plan, out);
            out.print("\n" + ESC + "[7m Press Enter to return to the editor " + ANSI_RESET);
            out.flush();
            waitForEnter();
        } finally {
            CodeRunner.cleanup(plan);
            resumeTerminal();
        }
        statusMessage = summary;
    }

    private static void waitForEnter() {
        try {
            int ch;
            while ((ch = System.in.read()) != -1 && ch != '\n') { /* Swallow keys until enter */ }
            if (ch == -1) running = false;
        } catch (IOException ignored) {
            running = false;
        }
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

    // ------------------------------------------------------------------
    // Render Thread
    // ------------------------------------------------------------------

    private static void renderLoop() {
        while (running) {
            try {
                if (needsRedraw && !renderPaused) {
                    needsRedraw = false; // Clear flag before reading state
                    String frame = buildFrame();
                    synchronized (RENDER_LOCK) {
                        if (!renderPaused) {
                            out.print(frame);
                            out.flush();
                        } else {
                            needsRedraw = true;
                        }
                    }
                }
            } catch (Throwable t) {
                statusMessage = "Render error: " + t;
            }
            try {
                Thread.sleep(16); // ~60fps rendering pace
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            // Animations (LSP spinner, fading status message) only need a repaint ~10x per second.
            long now = System.nanoTime();
            if (now - lastAnimTick > 100_000_000L && (statusFadePending || lspStarting())) {
                lastAnimTick = now;
                needsRedraw = true;
            }
            int minute = java.time.LocalTime.now().getMinute(); // keep the status-line clock current
            if (minute != lastClockMinute) {
                lastClockMinute = minute;
                needsRedraw = true;
            }
        }
    }

    private static long lastAnimTick = 0;

    private static boolean lspStarting() {
        LspDocumentSync s = D().lsp;
        return s != null && !s.client().ready().isDone();
    }

    private static String modeHint(Mode mode) {
        switch (mode) {
            case INSERT: return "-- INSERT --  Esc:normal  Tab:accept/indent  ↑↓:choose suggestion";
            case COMMAND: return HELP;
            default: return DEFAULT_STATUS;
        }
    }

    private static int modeColor(Mode mode) {
        switch (mode) {
            case INSERT: return 78;   // green
            case COMMAND: return 215; // orange
            default: return 75;       // blue
        }
    }

    /** Foreground colour of a status message, chosen by what it says. */
    private static String statusFg(String msg) {
        String m = msg.toLowerCase(java.util.Locale.ROOT);
        if (m.contains("unsaved")) return fg(214);
        if (m.contains("error") || m.contains("failed") || m.contains("not found") || m.contains("unknown")
                || m.contains("no language server") || m.contains("exited with code 1") || m.contains("can't")
                || m.contains("internal") || m.contains("too small")) return fg(203);
        if (m.startsWith("saved") || m.startsWith("loaded") || m.startsWith("undone") || m.startsWith("redone")
                || m.contains("compiled ok") || m.contains("syntax ok") || m.contains("exited with code 0")
                || m.contains("lsp stopped") || m.contains("cleared")) return fg(78);
        return fg(250);
    }

    private static char kindLetter(int kind) {
        switch (kind) {
            case 2: case 3: case 4: return 'f';          // method, function, constructor
            case 5: case 6: case 10: return 'v';         // field, variable, property
            case 7: case 8: case 22: case 13: return 'C'; // class, interface, struct, enum
            case 9: return 'm';                          // module
            case 14: return 'k';                         // keyword
            case 15: return 's';                         // snippet
            case 21: case 20: return 'c';                // constant, enum member
            default: return '·';
        }
    }

    private static int kindColor(int kind) {
        switch (kind) {
            case 2: case 3: case 4: return 75;
            case 5: case 6: case 10: return 81;
            case 7: case 8: case 22: case 13: return 221;
            case 9: return 141;
            case 14: return 177;
            case 21: case 20: return 215;
            default: return 245;
        }
    }

    private static String spaces(int n) {
        if (n <= 0) return "";
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) sb.append(' ');
        return sb.toString();
    }

    /** Lays out one full-width bar: coloured left text, coloured right text, filler in between. */
    private static String bar(String left, int leftLen, String right, int rightLen, int cols, String barBg) {
        StringBuilder sb = new StringBuilder(barBg);
        sb.append(left);
        if (leftLen + rightLen < cols) {
            sb.append(barBg).append(spaces(cols - leftLen - rightLen)).append(right);
        } else if (leftLen < cols) {
            sb.append(barBg).append(spaces(cols - leftLen)); // not enough room for the right side
        }
        return sb.append(ANSI_RESET).toString();
    }

    // ------------------------------------------------------------------
    // Split panes, git branch and small layout helpers
    // ------------------------------------------------------------------

    /** One screen line under construction: ANSI-coloured text plus its visible width. */
    private static final class Line {
        final StringBuilder colored = new StringBuilder();
        int len = 0;

        Line add(String style, String text) {
            colored.append(style).append(text);
            len += text.length();
            return this;
        }
    }

    // ---- Split screen ----

    private static Path normPath(String name) {
        try {
            return Paths.get(name.trim()).toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** A document already open in one of the panes under this file name, or null. */
    private static Doc findOpenDoc(String name) {
        Path want = normPath(name);
        if (want == null) return null;
        int n = split == Split.NONE ? 1 : 2;
        for (int i = 0; i < n; i++) {
            int idx = split == Split.NONE ? focus : i;
            if (want.equals(normPath(docs[idx].filename))) return docs[idx];
        }
        return null;
    }

    /**
     * :split / :vsplit. Without a file name the new pane shows the same document; with one it opens that
     * file (or shares the document if it is already open). The new pane gets the focus.
     */
    private static void openSplit(Split kind, String arg) {
        if (termCols < 40 || termRows < 10) {
            statusMessage = "Terminal too small to split.";
            return;
        }
        int other = 1 - focus;
        Doc target;
        boolean fresh = false;
        String loadMsg = null;
        if (arg.isEmpty()) {
            target = D();
        } else {
            target = findOpenDoc(arg);
            if (target == null) {
                TextEditor e = new TextEditor();
                loadMsg = e.loadFromFile(arg);
                if (loadMsg.startsWith("Error")) {
                    statusMessage = loadMsg;
                    return;
                }
                target = new Doc(e, arg.trim());
                fresh = true;
            }
        }

        if (split != Split.NONE) {
            if (arg.isEmpty()) { // only the layout changes
                split = kind;
                forceClear.set(true);
                statusMessage = (kind == Split.VERTICAL ? "Vertical" : "Horizontal") + " split.";
                return;
            }
            Doc old = docs[other];
            boolean shared = old == docs[focus];
            if (!shared && old.editor.isDirty()) {
                statusMessage = "The other pane has unsaved changes. Save it there or close it with :q!";
                return;
            }
            if (!shared && old != target) stopLsp(old, false);
        }

        docs[other] = target;
        if (target == D()) { // same document: the new pane starts at the current position
            savedRow[other] = cursorRow;
            savedCol[other] = cursorCol;
            savedGoal[other] = goalCol;
            paneTop[other] = paneTop[focus];
            paneLeft[other] = paneLeft[focus];
        } else {
            savedRow[other] = 0;
            savedCol[other] = 0;
            savedGoal[other] = 0;
            paneTop[other] = 0;
            paneLeft[other] = 0;
        }
        split = kind;
        forceClear.set(true);
        switchPane(); // the new pane gets the focus
        if (fresh) {
            startLsp(target);
            statusMessage = loadMsg;
        } else {
            statusMessage = (kind == Split.VERTICAL ? "Vertical" : "Horizontal") + " split. Tab/Ctrl+W switch pane, :q closes it.";
        }
    }

    /** Gives the cursor to the other pane. */
    private static void switchPane() {
        if (split == Split.NONE) return;
        if (showingSuggestions) closeSuggestions();
        int cur = focus;
        int other = 1 - cur;
        int row = cursorRow, col = cursorCol, goal = goalCol;
        cursorRow = savedRow[other];
        cursorCol = savedCol[other];
        goalCol = savedGoal[other];
        savedRow[cur] = row;
        savedCol[cur] = col;
        savedGoal[cur] = goal;
        focus = other;
        keepGoal = true;
        refreshGitBranch();
    }

    /**
     * Closes pane {@code idx}; the other pane stays. Refuses when the pane holds the only copy of a
     * document with unsaved changes, unless {@code force}.
     */
    private static boolean closePane(int idx, boolean force) {
        if (split == Split.NONE) return false;
        int other = 1 - idx;
        Doc gone = docs[idx];
        boolean shared = docs[other] == gone;
        if (!shared && gone.editor.isDirty() && !force) {
            statusMessage = "Unsaved changes in this pane! :w to save, :q! to discard.";
            return false;
        }
        if (showingSuggestions) closeSuggestions();
        if (focus == idx) { // hand the cursor to the surviving pane
            cursorRow = savedRow[other];
            cursorCol = savedCol[other];
            goalCol = savedGoal[other];
            keepGoal = true;
        }
        docs[0] = docs[other];
        paneTop[0] = paneTop[other];
        paneLeft[0] = paneLeft[other];
        focus = 0;
        split = Split.NONE;
        if (!shared) stopLsp(gone, false);
        forceClear.set(true);
        refreshGitBranch();
        return true;
    }

    /** Reads the current git branch (or short commit id) by looking for .git/HEAD above the open file. */
    private static void refreshGitBranch() {
        String result = "";
        try {
            Path file = Paths.get(D().filename.trim()).toAbsolutePath().normalize();
            for (Path p = file.getParent(); p != null; p = p.getParent()) {
                Path head = p.resolve(".git").resolve("HEAD");
                if (Files.isRegularFile(head)) {
                    String h = new String(Files.readAllBytes(head), StandardCharsets.UTF_8).trim();
                    result = h.startsWith("ref:") ? h.substring(h.lastIndexOf('/') + 1)
                            : h.substring(0, Math.min(7, h.length()));
                    break;
                }
            }
        } catch (IOException | RuntimeException ignored) { }
        gitBranch = result;
    }

    private static int[] diagCounts() {
        int errors = 0, warnings = 0;
        for (LspClient.Diagnostic d : D().diagnostics) {
            if (d.severity == 1) errors++;
            else if (d.severity == 2) warnings++;
        }
        return new int[]{errors, warnings};
    }

    private static String clip(String s, int max) {
        if (max <= 0) return "";
        return s.length() <= max ? s : s.substring(0, Math.max(0, max - 1)) + "…";
    }

    private static String repeat(String unit, int n) {
        StringBuilder sb = new StringBuilder(Math.max(0, n) * unit.length());
        for (int i = 0; i < n; i++) sb.append(unit);
        return sb.toString();
    }

    private static String baseName(String path) {
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    /** Right-hand side of the status line. `full` adds diagnostics and the scroll percentage. */
    private static Line statusRight(boolean full, int[] dc, int pct, String pos, String clock) {
        Line r = new Line();
        if (full) {
            if (dc[0] > 0) r.add(bg(235) + fg(203), "✖ " + dc[0] + "  ");
            if (dc[1] > 0) r.add(bg(235) + fg(214), "▲ " + dc[1] + "  ");
            r.add(bg(235) + fg(245), pct + "%  ");
        }
        r.add(bg(237) + fg(252), " " + pos + " ");
        r.add(bg(213) + fg(16) + BOLD, " " + clock + " ");
        r.add(BOLD_OFF, "");
        return r;
    }

    /**
     * Draws one pane (gutter + highlighted text) of a document at screen position (x, y), 1-based,
     * and scrolls it so its cursor is visible. Returns {visual cursor column, gutter width, clamped column}.
     */
    private static int[] drawPane(StringBuilder sb, int idx, Doc doc, int x, int y, int w, int h, int row, int col,
                                  boolean active, boolean eraseEol) {
        if (h <= 0 || w <= 0) return new int[]{0, 0, col};

        TextEditor ed = doc.editor;
        int lineCount = ed.getLineCount();
        row = Math.max(0, Math.min(row, lineCount - 1));
        SyntaxHighlighter highlighter = highlighterRegistry.forFileName(doc.filename);
        doc.cache.useHighlighter(highlighter);
        List<LspClient.Diagnostic> diags = doc.diagnostics;

        int digits = String.valueOf(lineCount).length();
        int gutterW = digits + 3;                   // marker + number + space + separator
        if (w - gutterW < 10) gutterW = 0;          // pathological narrow pane: drop the gutter
        int textCols = w - gutterW;

        // Vertical scroll
        int top = paneTop[idx];
        if (row < top) top = row;
        else if (row >= top + h) top = row - h + 1;
        top = Math.max(0, Math.min(top, Math.max(0, lineCount - 1)));

        // Only the visible lines are read; everything above is summarized by the highlight-state cache.
        List<String> visible = ed.getLines(top, top + h);
        String cursorLineText = (row >= top && row - top < visible.size())
                ? visible.get(row - top) : ed.getLine(row);
        col = Math.max(0, Math.min(col, cursorLineText.length()));

        // Horizontal scroll
        int left = paneLeft[idx];
        int vcol = visualCol(cursorLineText, col);
        if (vcol < left) left = vcol;
        else if (vcol >= left + textCols) left = vcol - textCols + 1;
        left = Math.max(0, left);
        paneTop[idx] = top;
        paneLeft[idx] = left;

        HighlightState state = doc.cache.stateAt(top, ed::getLines);
        boolean rel = relativeNumbers;
        int markColor = active ? 220 : 245;

        for (int i = 0; i < h; i++) {
            sb.append(ESC).append('[').append(y + i).append(';').append(x).append('H');
            int lineIdx = top + i;
            if (i >= visible.size()) {
                if (gutterW > 0) sb.append(fg(61)).append('~').append(ANSI_RESET);
                if (eraseEol) sb.append(ESC).append("[K");
                else sb.append(spaces(w - (gutterW > 0 ? 1 : 0)));
                continue;
            }
            if (gutterW > 0) {
                boolean current = lineIdx == row;
                LspClient.Diagnostic d = bestDiagnosticAt(diags, lineIdx);
                if (d != null) {
                    sb.append(fg(severityColor(d.severity))).append(d.severity == 1 ? "●" : d.severity == 2 ? "▲" : "•");
                } else if (current) {
                    sb.append(fg(markColor)).append('›');
                } else {
                    sb.append(' ');
                }
                // Relative numbering: the cursor line shows its real number (left aligned), the
                // others show their distance from it.
                String num = (current || !rel) ? String.valueOf(lineIdx + 1) : String.valueOf(Math.abs(lineIdx - row));
                String pad = spaces(digits - num.length());
                sb.append(current ? fg(markColor) + BOLD : fg(241))
                        .append(current && rel ? num + pad : pad + num).append(ANSI_RESET)
                        .append(' ').append(current ? fg(markColor) : fg(238)).append('│').append(ANSI_RESET);
            }
            String display = expandTabsAndSanitize(visible.get(i));
            HighlightResult hr = highlighter.highlightLine(display, state);
            state = hr.nextState;
            sb.append(sliceAnsi(hr.coloredLine, left, textCols));
            if (eraseEol) {
                sb.append(ESC).append("[K");
            } else {
                int printed = Math.max(0, Math.min(textCols, display.length() - left));
                sb.append(spaces(textCols - printed));
            }
        }
        return new int[]{vcol, gutterW, col};
    }

    private static String buildFrame() {
        int rows = termRows;
        int cols = termCols;
        StringBuilder sb = new StringBuilder(rows * (cols + 16));
        sb.append(ANSI_HIDE_CURSOR);
        if (forceClear.getAndSet(false)) sb.append(ANSI_CLEAR_SCREEN);

        if (rows < 5 || cols < 20) {
            sb.append(ESC).append("[1;1H").append(ANSI_CLEAR_LINE).append("Terminal too small");
            return sb.toString();
        }

        // --- Snapshot of Shared State ---
        Mode mode = currentMode;
        String status = statusMessage;
        String command = commandInput.toString();
        Split sp = split;
        int fo = focus;
        Doc ad = docs[fo]; // document of the focused pane
        String filename = ad.filename;
        List<LspDocumentSync.Completion> suggestions = showingSuggestions ? activeSuggestions : null;
        int selected = suggestionIndex;
        int row = cursorRow;
        int col = cursorCol;
        int textRows = rows - 3; // rows 2 .. rows-2: between the tab line and the status line
        long now = System.currentTimeMillis();

        // Tell the highlight-state cache which lines were edited since the previous frame. Done before
        // reading any text: an edit that sneaks in later is reported again and fixed on the next frame.
        int paneCount = sp == Split.NONE ? 1 : 2;
        for (int i = 0; i < paneCount; i++) {
            if (i == 1 && docs[1] == docs[0]) break; // same document in both panes: one report is enough
            int dirtyFrom = docs[i].editor.takeDirtyFromLine();
            if (dirtyFrom != Integer.MAX_VALUE) docs[i].cache.invalidateFrom(dirtyFrom);
        }

        int lineCount = ad.editor.getLineCount();
        row = Math.max(0, Math.min(row, lineCount - 1));

        // --- Pane layout: one pane, or two side by side / stacked ---
        int[] px = {1, 1}, py = {2, 2}, pw = {cols, cols}, ph = {textRows, textRows};
        if (sp == Split.VERTICAL) {
            int wl = (cols - 1) / 2;
            pw[0] = wl;
            px[1] = wl + 2;
            pw[1] = cols - wl - 1;
        } else if (sp == Split.HORIZONTAL) {
            int ht = (textRows - 1) / 2;
            ph[0] = ht;
            py[1] = 2 + ht + 1;
            ph[1] = textRows - ht - 1;
        }

        // --- Tab line (row 1): the open file as a tab, LSP badge on the right ---
        int[] dc = diagCounts();
        Line tab = new Line();
        boolean twoFiles = paneCount == 2 && docs[0] != docs[1];
        int tabMax = Math.max(8, cols / (twoFiles ? 5 : 3));
        for (int i = 0; i < (twoFiles ? 2 : 1); i++) {
            Doc td = twoFiles ? docs[i] : ad;
            boolean act = twoFiles ? i == fo : true;
            tab.add(bg(237) + (act ? fg(141) : fg(240)) + BOLD, " ▎")
                    .add(act ? fg(255) : fg(245), " " + clip(sanitize(baseName(td.filename)), tabMax)).add(BOLD_OFF, "");
            if (td.editor.isDirty()) tab.add(fg(214), " ●");
            if (act && dc[0] > 0) tab.add(fg(203), " ✖ " + dc[0]);
            if (act && dc[1] > 0) tab.add(fg(214), " ▲ " + dc[1]);
            tab.add(bg(237), " ");
        }
        tab.add(bg(234) + fg(240), "  + ");
        String[] badge = lspBadge();
        sb.append(ESC).append("[1;1H")
                .append(bar(tab.colored.toString(), tab.len, badge[0], badge[1].length(), cols, bg(234)));

        // --- Body: one or two panes ---
        int[] activeInfo = {0, 0, col}; // {visual column, gutter width, clamped column} of the focused pane
        for (int i = 0; i < paneCount; i++) {
            boolean active = i == fo;
            Doc pd = docs[i];
            int r = active ? row : Math.max(0, Math.min(savedRow[i], pd.editor.getLineCount() - 1));
            int c = active ? col : savedCol[i];
            boolean eraseEol = px[i] + pw[i] - 1 >= cols; // the pane touches the right edge of the screen
            int[] info = drawPane(sb, i, pd, px[i], py[i], pw[i], ph[i], r, c, active, eraseEol);
            if (active) activeInfo = info;
        }
        if (sp == Split.VERTICAL) {
            sb.append(fg(238));
            for (int k = 0; k < textRows; k++) {
                sb.append(ESC).append('[').append(2 + k).append(';').append(pw[0] + 1).append('H').append('│');
            }
            sb.append(ANSI_RESET);
        } else if (sp == Split.HORIZONTAL) {
            sb.append(ESC).append('[').append(py[0] + ph[0]).append(";1H").append(fg(238))
                    .append(repeat("─", cols)).append(ANSI_RESET);
        }
        int editorX = px[fo];
        int editorCols = pw[fo];
        int paneY = py[fo];
        int vcol = activeInfo[0];
        int gutterW = activeInfo[1];
        col = activeInfo[2];
        int topLine = paneTop[fo];
        int leftCol = paneLeft[fo];

        // --- Status line (second to last row): mode | branch | file ╱╱╱╱ diagnostics % row:col clock ---
        String hint = modeHint(mode);
        if (!status.equals(lastStatusSeen)) {
            lastStatusSeen = status;
            statusSince = now;
        }
        String shownStatus = status;
        statusFadePending = false;
        if (!status.equals(hint) && !status.equals(DEFAULT_STATUS)) {
            if (now - statusSince >= STATUS_TTL_MS) shownStatus = hint; // old message fades back to the hint
            else statusFadePending = true;
        }

        int mc = modeColor(mode);
        String branch = gitBranch;
        int pct = lineCount <= 1 ? 100 : (int) Math.round(100.0 * row / (lineCount - 1));
        java.time.LocalTime t = java.time.LocalTime.now();
        String clock = String.format(java.util.Locale.ROOT, "%02d:%02d", t.getHour(), t.getMinute());

        Line left = new Line();
        left.add(bg(mc) + fg(16) + BOLD, " " + mode + " ");
        if (!branch.isEmpty()) left.add(BOLD_OFF + bg(237) + fg(150), " " + clip(sanitize(branch), 24) + " ");
        left.add(BOLD_OFF + bg(235) + fg(252),
                " " + clip(sanitize(baseName(filename)), Math.max(8, cols / 3)) + (ed().isDirty() ? " ●" : "") + " ");

        String posText = (row + 1) + ":" + (col + 1);
        Line right = statusRight(true, dc, pct, posText, clock);
        if (left.len + right.len > cols) right = statusRight(false, dc, pct, posText, clock);
        if (left.len + right.len > cols) right = new Line();

        sb.append(ESC).append('[').append(rows - 1).append(";1H").append(bg(235))
                .append(sliceAnsi(left.colored.toString(), 0, cols));
        int fill = cols - Math.min(left.len, cols) - right.len;
        sb.append(bg(235)).append(fg(60)).append(BOLD_OFF);
        if (fill >= 3) sb.append(' ').append(repeat("╱", fill - 2)).append(' ');
        else sb.append(spaces(fill));
        sb.append(right.colored).append(ANSI_RESET);

        // --- Bottom line: command prompt, a fresh message, the diagnostic under the cursor, or a hint ---
        sb.append(ESC).append('[').append(rows).append(";1H").append(ANSI_CLEAR_LINE);
        String cursorSeq;
        if (mode == Mode.COMMAND) {
            String shown = ":" + sanitize(command);
            if (shown.length() > cols - 1) shown = shown.substring(shown.length() - (cols - 1));
            sb.append(fg(177)).append(BOLD).append(':').append(BOLD_OFF).append(FG_DEFAULT).append(shown, 1, shown.length());
            cursorSeq = ESC + "[" + rows + ";" + Math.min(shown.length() + 1, cols) + "H";
        } else {
            boolean isHint = shownStatus.equals(hint) || shownStatus.equals(DEFAULT_STATUS);
            LspClient.Diagnostic d = bestDiagnosticAt(ad.diagnostics, row);
            String text;
            String color;
            if (!isHint) {
                text = " " + shownStatus;
                color = statusFg(shownStatus);
            } else if (d != null) {
                String kind = d.severity == 1 ? "error" : d.severity == 2 ? "warning" : d.severity == 3 ? "info" : "hint";
                text = " " + kind + ": " + d.message.replace('\n', ' ').replace('\r', ' ')
                        + (d.source != null ? " [" + d.source + "]" : "");
                color = fg(severityColor(d.severity));
            } else {
                text = " " + hint;
                color = fg(240);
            }
            if (text.length() > cols - 1) text = text.substring(0, cols - 1);
            sb.append(color).append(sanitize(text)).append(ANSI_RESET);

            int screenRow = (row - topLine) + paneY;
            int screenCol = editorX + gutterW + (vcol - leftCol);
            cursorSeq = ESC + "[" + Math.max(paneY, Math.min(screenRow, rows - 2)) + ";"
                    + Math.max(editorX, Math.min(screenCol, cols)) + "H";
        }

        // --- Suggestions popup (drawn last so it floats above the text) ---
        if (mode == Mode.INSERT && suggestions != null && !suggestions.isEmpty()) {
            final int maxItems = 8;
            int total = suggestions.size();
            selected = Math.max(0, Math.min(selected, total - 1));
            int first = selected >= maxItems ? selected - maxItems + 1 : 0;
            int n = Math.min(maxItems, total - first);

            int width = 12;
            for (int k = first; k < first + n; k++) {
                LspDocumentSync.Completion c = suggestions.get(k);
                width = Math.max(width, 4 + c.label.length() + (c.detail.isEmpty() ? 0 : 2 + Math.min(c.detail.length(), 28)));
            }
            width = Math.min(width, Math.min(editorCols, 64));

            int cursorScreenRow = (row - topLine) + paneY;
            int blockH = n + 1; // items + footer
            int popupRow = cursorScreenRow + 1;
            if (popupRow + blockH - 1 > rows - 2) popupRow = Math.max(2, cursorScreenRow - blockH);
            int startVcol = vcol - Math.max(0, col - Math.min(suggestions.get(0).replaceFromCol, col));
            int popupCol = Math.max(editorX, Math.min(editorX + gutterW + (startVcol - leftCol), cols - width + 1));

            for (int k = 0; k < n; k++) {
                LspDocumentSync.Completion c = suggestions.get(first + k);
                boolean sel = first + k == selected;
                String label = sanitize(c.label.replace('\n', ' '));
                String detail = c.detail.isEmpty() ? "" : sanitize(c.detail.replace('\n', ' '));
                int room = width - 4 - label.length();
                if (label.length() > width - 4) label = label.substring(0, Math.max(0, width - 4));
                if (detail.length() > room - 2) detail = room > 3 ? detail.substring(0, room - 3) + "…" : "";
                int pad = Math.max(0, width - 4 - label.length() - (detail.isEmpty() ? 0 : detail.length() + 2));

                sb.append(ESC).append('[').append(popupRow + k).append(';').append(popupCol).append('H')
                        .append(sel ? bg(24) : bg(236))
                        .append(' ').append(fg(kindColor(c.kind))).append(BOLD).append(kindLetter(c.kind)).append(BOLD_OFF)
                        .append(' ').append(sel ? fg(231) + BOLD : fg(252)).append(label).append(BOLD_OFF)
                        .append(detail.isEmpty() ? "" : "  " + fg(sel ? 117 : 245) + detail)
                        .append(spaces(pad)).append(' ').append(ANSI_RESET);
            }
            String footer = " " + (selected + 1) + "/" + total + "  Tab accept  Esc close";
            if (footer.length() > width) footer = footer.substring(0, width);
            sb.append(ESC).append('[').append(popupRow + n).append(';').append(popupCol).append('H')
                    .append(bg(235)).append(fg(244)).append(footer).append(spaces(width - footer.length()))
                    .append(ANSI_RESET);
        }

        // Cursor shape follows the mode: bar while typing, block otherwise. Position goes last so the
        // popup drawing above can never leave the cursor inside it.
        sb.append(ESC).append(mode == Mode.INSERT ? "[6 q" : "[2 q");
        sb.append(cursorSeq);
        sb.append(ANSI_SHOW_CURSOR);
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Text Processing Utilities
    // ------------------------------------------------------------------

    private static boolean isControl(char c) {
        return c < 0x20 || (c >= 0x7F && c < 0xA0);
    }

    /** Prevents file contents from injecting terminal escape sequences. */
    private static String sanitize(String s) {
        boolean clean = true;
        for (int i = 0; i < s.length(); i++) if (isControl(s.charAt(i))) { clean = false; break; }
        if (clean) return s;
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            sb.append(isControl(c) ? '·' : c);
        }
        return sb.toString();
    }

    /** Converts tabs to spaces up to the next tab stop. */
    private static String expandTabsAndSanitize(String line) {
        StringBuilder sb = new StringBuilder(line.length() + 8);
        int v = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\t') {
                int n = TAB_WIDTH - (v % TAB_WIDTH);
                for (int k = 0; k < n; k++) sb.append(' ');
                v += n;
            } else {
                sb.append(isControl(c) ? '·' : c);
                v++;
            }
        }
        return sb.toString();
    }

    /** Calculates screen column index after tabs are expanded. */
    private static int visualCol(String line, int col) {
        int v = 0;
        int end = Math.min(col, line.length());
        for (int i = 0; i < end; i++) {
            v += line.charAt(i) == '\t' ? TAB_WIDTH - (v % TAB_WIDTH) : 1;
        }
        return v;
    }

    /** Slices an ANSI-colored string to fit the viewport horizontally. */
    static String sliceAnsi(String s, int start, int width) {
        StringBuilder sb = new StringBuilder(s.length());
        int end = start + width;
        int col = 0;
        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == 0x1b && i + 1 < n && s.charAt(i + 1) == '[') {
                int j = i + 2;
                while (j < n && !(s.charAt(j) >= 0x40 && s.charAt(j) <= 0x7E)) j++;
                if (j < n) j++;
                if (col < end) sb.append(s, i, j);
                i = j;
                continue;
            }
            if (col >= end) break;
            int cp = s.codePointAt(i);
            int len = Character.charCount(cp);
            if (col >= start && col + len <= end) sb.append(s, i, i + len);
            col += len;
            i += len;
        }
        sb.append(ANSI_RESET);
        return sb.toString();
    }

    private static String padRight(String text, int width) {
        if (width <= 0) return "";
        if (text.length() >= width) return text.substring(0, width);
        StringBuilder sb = new StringBuilder(width);
        sb.append(text);
        while (sb.length() < width) sb.append(' ');
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Terminal System Handling
    // ------------------------------------------------------------------

    /** Executes standard `stty` commands to manipulate terminal properties. */
    private static String stty(String args) {
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

    private static void updateTerminalSize() {
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

    private static void resizeWatcher() {
        while (running) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                return;
            }
            if (!renderPaused) updateTerminalSize();
        }
    }

    private static void enterEditorScreen() {
        stty("raw -echo");
        out.print(ANSI_ENTER_ALT_SCREEN + ANSI_CLEAR_SCREEN + ANSI_HIDE_CURSOR);
        out.flush();
    }

    /** Surrenders terminal control to user programs executed via :run */
    private static void suspendTerminal() {
        synchronized (RENDER_LOCK) {
            renderPaused = true;
        }
        out.print(ANSI_RESET + ESC + "[0 q" + ANSI_SHOW_CURSOR + ANSI_LEAVE_ALT_SCREEN);
        out.flush();
        stty(originalStty);
    }

    /** Regains terminal control after a user program exits. */
    private static void resumeTerminal() {
        stty("raw -echo");
        out.print(ANSI_ENTER_ALT_SCREEN + ANSI_CLEAR_SCREEN + ANSI_HIDE_CURSOR);
        out.flush();
        updateTerminalSize();
        forceClear.set(true);
        needsRedraw = true;
        renderPaused = false;
    }

    private static void restoreTerminal() {
        if (!terminalRestored.compareAndSet(false, true)) return;
        out.print(ANSI_RESET + ESC + "[0 q" + ANSI_SHOW_CURSOR + ANSI_LEAVE_ALT_SCREEN);
        out.flush();
        if (originalStty != null) {
            if (stty(originalStty) == null) stty("sane");
        }
    }
}