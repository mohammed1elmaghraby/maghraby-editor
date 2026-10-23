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

    private static final TextEditor editor = new TextEditor();
    private static final HighlighterRegistry highlighterRegistry = new HighlighterRegistry();
    private static final HighlightStateCache stateCache = new HighlightStateCache(); // Render thread only
    private static volatile String currentFilename = "Untitled.txt";

    // --- LSP (optional: null when no language server is installed for the current file) ---
    private static volatile LspDocumentSync lsp = null;
    // Diagnostics of the open file. The list is replaced as a whole (never mutated), so the render
    // thread can read it without locking.
    private static volatile List<LspClient.Diagnostic> diagnostics = new ArrayList<>();

    // 2D Cursor Coordinates (Character coordinates inside the buffer)
    private static volatile int cursorRow = 0;
    private static volatile int cursorCol = 0;
    private static int goalCol = 0;          // Remembered column for Up/Down movement (Input thread only)
    private static boolean keepGoal = false; // Flag set by vertical moves (Input thread only)

    // Viewport Boundaries (Render thread only)
    private static int topLine = 0;
    private static int leftCol = 0;

    private static final String DEFAULT_STATUS =
            "i:insert  ::command  Tab:chat  u:undo  r:redo  F5:run  q:quit  (:help)";
    private static final String HELP =
            ":w [file]  :wq  :q  :q!  :run [args]  :compile  :lsp [stop]  :chat [clear|stop]  :set rnu|nornu  |  Tab chat  F5 run  Ctrl+S save  Ctrl+Q force-quit";

    private static volatile String statusMessage = DEFAULT_STATUS;
    private static volatile Mode currentMode = Mode.NORMAL;
    private static final StringBuffer commandInput = new StringBuffer();

    // --- Chat side panel (left), relative line numbers and git branch (status line) ---
    private static volatile boolean chatVisible = true;
    private static final StringBuffer chatInput = new StringBuffer();
    private static volatile int chatScroll = 0; // lines scrolled back from the newest message
    private static final Chat chat = new Chat(() -> needsRedraw = true);
    private static volatile boolean relativeNumbers = true;
    private static volatile String gitBranch = "";
    private static int lastClockMinute = -1;    // Render thread only

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
            LspDocumentSync s = lsp;
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

    enum Mode { NORMAL, INSERT, COMMAND, CHAT }

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
            currentFilename = args[0].trim();
            statusMessage = editor.loadFromFile(currentFilename);
            startLsp(currentFilename); // background: never delays startup
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
            chat.close();
            stopLsp(true);
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
            int[] pos = editor.insertAt(cursorRow, cursorCol, s);
            cursorRow = pos[0];
            cursorCol = pos[1];
            triggerCompletion();
        } else if (currentMode == Mode.CHAT) {
            if (chatInput.length() < 4000) chatInput.append(s);
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
                statusMessage = editor.undo(pos);
                cursorRow = pos[0];
                cursorCol = pos[1];
            } else if (c == 'r') {
                int[] pos = {cursorRow, cursorCol};
                statusMessage = editor.redo(pos);
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
        String line = editor.getLine(cursorRow);
        int end = Math.min(cursorCol, line.length());
        int start = end;
        while (start > 0 && Character.isJavaIdentifierPart(line.charAt(start - 1))) start--;
        return line.substring(start, end);
    }

    /** True when the character before the cursor is a member-access trigger: '.', '->' or '::'. */
    private static boolean afterTriggerChar() {
        String line = editor.getLine(cursorRow);
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
        LspDocumentSync s = lsp;
        if (s == null) return;
        String prefix = identifierPrefix();
        if (prefix.isEmpty() && !afterTriggerChar()) {
            if (showingSuggestions) closeSuggestions(); else s.cancelSuggestions();
            return;
        }
        s.requestSuggestions(cursorRow, cursorCol, prefix, Main::showSuggestionsUi);
    }

    /** Replaces the typed prefix with the highlighted suggestion. */
    private static void acceptSuggestion() {
        List<LspDocumentSync.Completion> list = activeSuggestions;
        if (list == null || list.isEmpty()) return;
        LspDocumentSync.Completion c = list.get(Math.max(0, Math.min(suggestionIndex, list.size() - 1)));
        closeSuggestions();

        int from = Math.min(c.replaceFromCol, cursorCol);
        for (int n = cursorCol - from; n > 0; n--) {
            int[] pos = editor.deleteAt(cursorRow, cursorCol);
            cursorRow = pos[0];
            cursorCol = pos[1];
        }
        int[] pos = editor.insertAt(cursorRow, cursorCol, c.insertText);
        cursorRow = pos[0];
        cursorCol = pos[1];
    }

    private static boolean suggestionsOpen() {
        List<LspDocumentSync.Completion> l = activeSuggestions;
        return showingSuggestions && l != null && !l.isEmpty();
    }

    private static void handleTab() {
        if (currentMode == Mode.NORMAL) { enterChat(); return; }
        if (currentMode == Mode.CHAT) { leaveChat(); return; }
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
            int[] pos = editor.deleteAt(cursorRow, cursorCol);
            cursorRow = pos[0];
            cursorCol = pos[1];
            if (showingSuggestions) triggerCompletion(); // narrows, or closes when nothing is left to complete
        } else if (currentMode == Mode.CHAT) {
            int n = chatInput.length();
            if (n > 0) {
                int cut = n >= 2 && Character.isLowSurrogate(chatInput.charAt(n - 1))
                        && Character.isHighSurrogate(chatInput.charAt(n - 2)) ? 2 : 1;
                chatInput.setLength(n - cut);
            }
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
        int[] pos = editor.deleteForward(cursorRow, cursorCol);
        cursorRow = pos[0];
        cursorCol = pos[1];
    }

    private static void handleEnter() {
        if (currentMode == Mode.INSERT) {
            if (showingSuggestions) closeSuggestions(); else if (lsp != null) lsp.cancelSuggestions();

            // Auto-indent: carry over the current line's indentation, one level deeper after an opener.
            String before = editor.getLine(cursorRow);
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
            int[] pos = editor.insertAt(cursorRow, cursorCol, insert.toString());
            cursorRow = pos[0];
            cursorCol = pos[1];
        } else if (currentMode == Mode.CHAT) {
            sendChat();
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
        if (currentMode == Mode.CHAT) { // Up/Down scroll the conversation (Up = older)
            if (rowDelta != 0) chatScroll = Math.max(0, chatScroll - rowDelta);
            return;
        }
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
                cursorCol = editor.getLineLength(cursorRow);
            }
        } else if (colDelta > 0) {
            if (cursorCol < editor.getLineLength(cursorRow)) {
                cursorCol++;
            } else if (cursorRow < editor.getLineCount() - 1) {
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
        if (currentMode == Mode.NORMAL || currentMode == Mode.INSERT) cursorCol = editor.getLineLength(cursorRow);
    }

    private static void handlePage(int direction) {
        if (currentMode == Mode.COMMAND || currentMode == Mode.CHAT) return;
        if (showingSuggestions) closeSuggestions();
        int step = Math.max(1, termRows - 4);
        cursorRow += direction * step;
        cursorCol = goalCol;
        keepGoal = true;
    }

    private static void clampCursor() {
        int maxRow = Math.max(0, editor.getLineCount() - 1);
        cursorRow = Math.max(0, Math.min(cursorRow, maxRow));
        cursorCol = Math.max(0, Math.min(cursorCol, editor.getLineLength(cursorRow)));
    }

    // ------------------------------------------------------------------
    // Editor Commands Execution
    // ------------------------------------------------------------------

    private static void tryQuit() {
        if (editor.isDirty()) {
            statusMessage = "Unsaved changes! :w to save, :wq to save+quit, :q! (or Ctrl+Q) to discard.";
        } else {
            running = false;
        }
    }

    private static boolean saveCommand(String arg) {
        String target = arg.isEmpty() ? currentFilename : arg;
        String msg = editor.saveToFile(target);
        statusMessage = msg;
        boolean ok = msg.startsWith("Saved");
        if (ok) {
            boolean renamed = !target.equals(currentFilename);
            currentFilename = target;
            refreshGitBranch();
            if (renamed) startLsp(target); // a new name can mean a different language (Untitled.txt -> a.go)
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
            case "q!": case "quit!": running = false; break;
            case "w": case "write": saveCommand(arg); break;
            case "wq": case "x": if (saveCommand(arg)) running = false; break;
            case "run": runCommand(true, arg); break;
            case "compile": case "build": runCommand(false, arg); break;
            case "lsp":
                if (arg.equals("stop")) {
                    stopLsp();
                    statusMessage = "LSP stopped.";
                } else {
                    startLsp(currentFilename);
                }
                break;
            case "chat":
                if (arg.equals("clear")) {
                    chat.clear();
                    chatScroll = 0;
                    statusMessage = "Chat cleared.";
                } else if (arg.equals("stop")) {
                    chat.cancel();
                    statusMessage = "Chat request cancelled.";
                } else {
                    chatVisible = !chatVisible;
                    statusMessage = chatVisible ? "Chat panel shown." : "Chat panel hidden.";
                }
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

    /** (Re)starts the language server for `filename`. Everything slow happens on background threads. */
    private static void startLsp(String filename) {
        stopLsp();
        LspDocumentSync sync = LspDocumentSync.tryStart(filename, editor, new LspClient.Listener() {
            @Override
            public void onDiagnostics(String uri, List<LspClient.Diagnostic> list) { // reader thread
                LspDocumentSync s = lsp;
                if (s != null && s.isCurrent(uri)) {
                    diagnostics = list;
                    needsRedraw = true;
                }
            }

            @Override
            public void onExit(String reason) { // reader thread
                LspDocumentSync s = lsp;
                lsp = null;
                diagnostics = new ArrayList<>();
                statusMessage = "LSP server exited. :lsp to restart";
                needsRedraw = true;
                if (s != null) {
                    Thread t = new Thread(s::close, "LSP-Cleanup");
                    t.setDaemon(true);
                    t.start();
                }
            }
        });
        lsp = sync;
        if (sync != null) {
            sync.open(Paths.get(filename.trim()).toAbsolutePath().normalize());
        } else {
            statusMessage = "No language server found for '" + filename + "' (is it installed and on PATH?)";
        }
    }

    private static void stopLsp() {
        stopLsp(false);
    }

    /**
     * Shutting a server down can take over a second, so unless we are quitting it happens on a
     * background thread and the editor never stalls.
     */
    private static void stopLsp(boolean blocking) {
        LspDocumentSync s = lsp;
        lsp = null;
        diagnostics = new ArrayList<>();
        if (s == null) return;
        if (blocking) {
            s.close();
        } else {
            Thread t = new Thread(s::close, "LSP-Cleanup");
            t.setDaemon(true);
            t.start();
        }
    }

    /** Right-hand header badge for the language server: {coloured, plain} (plain is used to measure width). */
    private static String[] lspBadge() {
        LspDocumentSync s = lsp;
        if (s == null) return new String[]{"", ""};
        if (!s.client().ready().isDone()) {
            String frame = SPINNER[(int) ((System.currentTimeMillis() / 100) % SPINNER.length)];
            String plain = frame + " LSP starting ";
            return new String[]{fg(221) + plain + FG_DEFAULT, plain};
        }
        int errors = 0, warnings = 0;
        for (LspClient.Diagnostic d : diagnostics) {
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
    private static LspClient.Diagnostic bestDiagnosticAt(int row) {
        LspClient.Diagnostic best = null;
        for (LspClient.Diagnostic d : diagnostics) {
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
        String file = currentFilename;
        if (CodeRunner.detect(file) == null) {
            statusMessage = "Can't " + (run ? "run" : "compile") + " '" + file + "'. Supported: "
                    + CodeRunner.supportedExtensions() + "  (rename with :w name.ext)";
            return;
        }
        String saved = editor.saveToFile(file);
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
            if (now - lastAnimTick > 100_000_000L && (statusFadePending || lspStarting() || chat.busy())) {
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
        LspDocumentSync s = lsp;
        return s != null && !s.client().ready().isDone();
    }

    private static String modeHint(Mode mode) {
        switch (mode) {
            case INSERT: return "-- INSERT --  Esc:normal  Tab:accept/indent  ↑↓:choose suggestion";
            case CHAT: return "-- CHAT --  Enter:send  Esc/Tab:editor  ↑↓:scroll  /clear  /stop";
            case COMMAND: return HELP;
            default: return DEFAULT_STATUS;
        }
    }

    private static int modeColor(Mode mode) {
        switch (mode) {
            case INSERT: return 78;   // green
            case COMMAND: return 215; // orange
            case CHAT: return 141;    // violet
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
    // Chat panel, git branch and small layout helpers
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

    /** The rendered chat panel: one pre-padded string per row, plus where the text cursor goes. */
    private static final class ChatView {
        String[] cells;
        int cursorRow = 0; // 0-based panel row of the input line
        int cursorCol = 0; // 0-based panel column of the text cursor
    }

    private static void enterChat() {
        if (termCols < 80 || termRows < 11) {
            statusMessage = "Terminal too small for the chat panel (needs 80x11).";
            return;
        }
        chatVisible = true;
        currentMode = Mode.CHAT;
        statusMessage = modeHint(Mode.CHAT);
    }

    private static void leaveChat() {
        currentMode = Mode.NORMAL;
        statusMessage = "NORMAL MODE";
    }

    private static void sendChat() {
        String text = chatInput.toString().trim();
        if (text.isEmpty()) return;

        if (text.startsWith("/")) { // panel commands never reach the backend
            chatInput.setLength(0);
            switch (text) {
                case "/clear": chat.clear(); chatScroll = 0; statusMessage = "Chat cleared."; break;
                case "/stop": chat.cancel(); statusMessage = "Chat request cancelled."; break;
                default: statusMessage = "Unknown chat command: " + text + "   (/clear, /stop)";
            }
            return;
        }
        if (chat.busy()) {
            statusMessage = "Still waiting for the previous answer. /stop cancels it.";
            return;
        }

        // The backend gets a little context about what is open; the panel only shows what was typed.
        Path file = null;
        try {
            file = Paths.get(currentFilename.trim()).toAbsolutePath().normalize();
        } catch (RuntimeException ignored) { }
        String prompt = text;
        Path workDir = null;
        if (file != null) {
            prompt = "I'm editing " + file + " (cursor at line " + (cursorRow + 1) + ", column "
                    + (cursorCol + 1) + ").\n\n" + text;
            workDir = LspDocumentSync.findRoot(file);
        }
        chatInput.setLength(0);
        chatScroll = 0;
        chat.send(text, prompt, workDir);
    }

    /** Reads the current git branch (or short commit id) by looking for .git/HEAD above the open file. */
    private static void refreshGitBranch() {
        String result = "";
        try {
            Path file = Paths.get(currentFilename.trim()).toAbsolutePath().normalize();
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
        for (LspClient.Diagnostic d : diagnostics) {
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

    private static String shortDir() {
        String dir = System.getProperty("user.dir", ".");
        String home = System.getProperty("user.home", "");
        if (!home.isEmpty() && dir.startsWith(home)) dir = "~" + dir.substring(home.length());
        return dir;
    }

    /** A Line cut or padded to exactly `width` cells. */
    private static String cell(Line l, int width) {
        String s = sliceAnsi(l.colored.toString(), 0, width);
        return s + spaces(width - Math.min(l.len, width)) + ANSI_RESET;
    }

    /** Word-wraps text to `width` columns, keeping explicit line breaks. */
    private static List<String> wrap(String text, int width) {
        List<String> out = new ArrayList<>();
        if (width < 1) return out;
        for (String raw : text.split("\n", -1)) { // split first: sanitize() would turn '\n' into '·'
            String para = sanitize(raw);
            if (para.isEmpty()) {
                out.add("");
                continue;
            }
            int i = 0;
            while (i < para.length()) {
                int end = Math.min(para.length(), i + width);
                boolean brokeAtSpace = false;
                if (end < para.length()) {
                    int sp = para.lastIndexOf(' ', end);
                    if (sp > i) {
                        end = sp;
                        brokeAtSpace = true;
                    } else if (end > i + 1 && Character.isLowSurrogate(para.charAt(end))) {
                        end--; // never split a surrogate pair
                    }
                }
                out.add(para.substring(i, end).stripTrailing());
                i = end;
                if (brokeAtSpace) while (i < para.length() && para.charAt(i) == ' ') i++;
            }
        }
        return out;
    }

    /**
     * Lays out the chat panel: header, conversation (newest at the bottom, scrollable), a rule,
     * the input line and a hint line. Every returned row is exactly `w` cells wide.
     */
    private static ChatView buildChatPanel(int w, int h, boolean focused, long now) {
        ChatView v = new ChatView();
        v.cells = new String[h];
        Line blank = new Line();
        int inner = Math.max(1, w - 2);
        int msgRows = h - 5;

        Line head = new Line();
        head.add(fg(141) + BOLD, " MAGHRABY ").add(fg(60) + BOLD_OFF, "/// ")
                .add(fg(245), clip(shortDir() + " · " + Chat.backendLabel(), w - 15));
        v.cells[0] = cell(head, w);
        v.cells[1] = cell(blank, w);

        List<Line> all = new ArrayList<>();
        for (Chat.Message m : chat.messages()) {
            if (m.role == Chat.Role.USER) {
                for (String l : wrap(m.text, w - 4)) {
                    all.add(new Line().add(fg(141) + BOLD_OFF, " │ ").add(fg(252), l));
                }
            } else {
                String color = m.role == Chat.Role.ERROR ? fg(203) : fg(252);
                for (String l : wrap(m.text, inner)) all.add(new Line().add(color, " " + l));
                if (!m.meta.isEmpty()) {
                    all.add(new Line());
                    all.add(new Line().add(fg(240), " " + clip(m.meta, inner)));
                }
            }
            all.add(new Line());
        }
        if (chat.busy()) {
            String frame = SPINNER[(int) ((now / 100) % SPINNER.length)];
            long secs = Math.max(0, (now - chat.busySince()) / 1000);
            all.add(new Line().add(fg(221), " " + frame + " Thinking… " + secs + "s"));
        }

        int total = all.size();
        int scroll = Math.min(chatScroll, Math.max(0, total - msgRows));
        if (scroll != chatScroll) chatScroll = scroll; // the user scrolled past the oldest line
        int start = Math.max(0, total - msgRows - scroll);
        for (int k = 0; k < msgRows; k++) {
            int idx = start + k;
            v.cells[2 + k] = cell(idx < total ? all.get(idx) : blank, w);
        }

        v.cells[h - 3] = cell(new Line().add(focused ? fg(141) : fg(238), " " + repeat("─", inner)), w);

        String typed = sanitize(chatInput.toString().replace('\n', ' '));
        int avail = Math.max(1, inner - 2);
        String shown = typed.length() > avail ? typed.substring(typed.length() - avail) : typed;
        Line in = new Line().add(fg(78) + BOLD, " ❯ ");
        if (typed.isEmpty()) in.add(fg(240) + BOLD_OFF, "Ready...");
        else in.add(fg(252) + BOLD_OFF, shown);
        v.cells[h - 2] = cell(in, w);
        v.cursorRow = h - 2;
        v.cursorCol = 3 + shown.length();

        String hint = focused ? "enter send · esc back · ↑↓ scroll · /clear" : "tab focus chat · :chat hide · /clear";
        v.cells[h - 1] = cell(new Line().add(fg(240), " " + clip(hint, inner)), w);
        return v;
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
        String filename = currentFilename;
        List<LspDocumentSync.Completion> suggestions = showingSuggestions ? activeSuggestions : null;
        int selected = suggestionIndex;
        int row = cursorRow;
        int col = cursorCol;
        int textRows = rows - 3; // rows 2 .. rows-2: between the tab line and the status line
        long now = System.currentTimeMillis();

        // --- Split layout: chat panel on the left, editor on the right ---
        int chatW = (chatVisible && cols >= 80 && textRows >= 8)
                ? Math.min(Math.max(30, cols * 2 / 5), cols - 31) : 0;
        if (mode == Mode.CHAT && chatW == 0) { // the terminal shrank below what the panel needs
            currentMode = Mode.NORMAL;
            mode = Mode.NORMAL;
        }
        int editorX = chatW > 0 ? chatW + 2 : 1;   // 1-based screen column of the editor's first cell
        int editorCols = cols - (editorX - 1);

        // Tell the highlight-state cache which lines were edited since the previous frame. Done before
        // reading any text: an edit that sneaks in later is reported again and fixed on the next frame.
        int dirtyFrom = editor.takeDirtyFromLine();
        if (dirtyFrom != Integer.MAX_VALUE) stateCache.invalidateFrom(dirtyFrom);

        int lineCount = editor.getLineCount();
        row = Math.max(0, Math.min(row, lineCount - 1));

        // --- Gutter geometry ---
        int digits = String.valueOf(lineCount).length();
        int gutterW = digits + 3;                 // marker + number + space + separator
        if (editorCols - gutterW < 10) gutterW = 0; // pathological narrow editor: drop the gutter
        int textCols = editorCols - gutterW;

        // --- Viewport Calculation (Vertical) ---
        if (row < topLine) topLine = row;
        else if (row >= topLine + textRows) topLine = row - textRows + 1;
        topLine = Math.max(0, Math.min(topLine, Math.max(0, lineCount - 1)));

        // Only the visible lines are read; everything above is summarized by the highlight-state cache.
        List<String> visible = editor.getLines(topLine, topLine + textRows);
        String cursorLineText = (row >= topLine && row - topLine < visible.size())
                ? visible.get(row - topLine) : editor.getLine(row);
        col = Math.max(0, Math.min(col, cursorLineText.length()));

        // --- Viewport Calculation (Horizontal) ---
        int vcol = visualCol(cursorLineText, col);
        if (vcol < leftCol) leftCol = vcol;
        else if (vcol >= leftCol + textCols) leftCol = vcol - textCols + 1;
        leftCol = Math.max(0, leftCol);

        // --- Tab line (row 1): the open file as a tab, LSP badge on the right ---
        int[] dc = diagCounts();
        String tabName = clip(sanitize(baseName(filename)), Math.max(8, cols / 3));
        Line tab = new Line();
        tab.add(bg(237) + fg(141) + BOLD, " ▎").add(fg(255), " " + tabName).add(BOLD_OFF, "");
        if (editor.isDirty()) tab.add(fg(214), " ●");
        if (dc[0] > 0) tab.add(fg(203), " ✖ " + dc[0]);
        if (dc[1] > 0) tab.add(fg(214), " ▲ " + dc[1]);
        tab.add(bg(237), " ").add(bg(234) + fg(240), "  + ");
        String[] badge = lspBadge();
        sb.append(ESC).append("[1;1H")
                .append(bar(tab.colored.toString(), tab.len, badge[0], badge[1].length(), cols, bg(234)));

        // --- Body: chat panel | editor ---
        ChatView chatView = chatW > 0 ? buildChatPanel(chatW, textRows, mode == Mode.CHAT, now) : null;

        SyntaxHighlighter highlighter = highlighterRegistry.forFileName(filename);

        // Highlighter state at the top of the screen (multi-line comments / docstrings opened above it).
        // The cache only scans lines it has not seen yet, instead of lines 0..topLine on every frame.
        stateCache.useHighlighter(highlighter);
        HighlightState state = stateCache.stateAt(topLine, editor::getLines);

        boolean rel = relativeNumbers;
        for (int i = 0; i < textRows; i++) {
            sb.append(ESC).append('[').append(i + 2).append(";1H");
            if (chatView != null) {
                sb.append(chatView.cells[i])
                        .append(fg(mode == Mode.CHAT ? 141 : 238)).append('│').append(ANSI_RESET);
            }
            int lineIdx = topLine + i;
            if (gutterW > 0) {
                if (i < visible.size()) {
                    boolean current = lineIdx == row;
                    LspClient.Diagnostic d = bestDiagnosticAt(lineIdx);
                    if (d != null) {
                        sb.append(fg(severityColor(d.severity))).append(d.severity == 1 ? "●" : d.severity == 2 ? "▲" : "•");
                    } else if (current) {
                        sb.append(fg(220)).append('›');
                    } else {
                        sb.append(' ');
                    }
                    // Relative numbering: the cursor line shows its real number (left aligned), the
                    // others show their distance from it.
                    String num = (current || !rel) ? String.valueOf(lineIdx + 1) : String.valueOf(Math.abs(lineIdx - row));
                    String pad = spaces(digits - num.length());
                    sb.append(current ? fg(220) + BOLD : fg(241))
                            .append(current && rel ? num + pad : pad + num).append(ANSI_RESET)
                            .append(' ').append(current ? fg(220) : fg(238)).append('│').append(ANSI_RESET);
                } else {
                    sb.append(fg(61)).append('~').append(ANSI_RESET);
                }
            }
            if (i < visible.size()) {
                String display = expandTabsAndSanitize(visible.get(i));
                HighlightResult hr = highlighter.highlightLine(display, state);
                state = hr.nextState;
                sb.append(sliceAnsi(hr.coloredLine, leftCol, textCols));
            }
            sb.append(ESC).append("[K"); // erase the rest of the row; the chat panel is to the left
        }

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
                " " + clip(sanitize(baseName(filename)), Math.max(8, cols / 3)) + (editor.isDirty() ? " ●" : "") + " ");

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
            LspClient.Diagnostic d = bestDiagnosticAt(row);
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

            if (mode == Mode.CHAT && chatView != null) {
                cursorSeq = ESC + "[" + (2 + chatView.cursorRow) + ";" + Math.min(chatView.cursorCol + 1, chatW) + "H";
            } else {
                int screenRow = (row - topLine) + 2;
                int screenCol = editorX + gutterW + (vcol - leftCol);
                cursorSeq = ESC + "[" + Math.max(2, Math.min(screenRow, rows - 2)) + ";"
                        + Math.max(editorX, Math.min(screenCol, cols)) + "H";
            }
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

            int cursorScreenRow = (row - topLine) + 2;
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
        sb.append(ESC).append(mode == Mode.INSERT || mode == Mode.CHAT ? "[6 q" : "[2 q");
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