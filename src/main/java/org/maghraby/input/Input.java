package org.maghraby.input;

import org.maghraby.lsp.LspDocumentSync;
import org.maghraby.core.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.maghraby.core.State.*;
import static org.maghraby.ui.Panes.*;
import static org.maghraby.input.Autocomplete.*;
import static org.maghraby.input.Commands.*;
import static org.maghraby.ui.Terminal.termRows;

/**
 * Input thread: reads bytes, decodes escape sequences and dispatches keys by mode.
 */
public final class Input {
    private Input() {}

    public static void inputLoop() throws IOException {
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
    public static int readTimeout(InputStream in, int ms) throws IOException {
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

    public static String readUtf8(InputStream in, int lead) throws IOException {
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
    public static void handleEscapeSequence(InputStream in) throws IOException {
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

    public static void handleText(String s) {
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

    public static void handleTab() {
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

    public static void handleBackspace() {
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

    public static void handleDeleteKey() {
        if (currentMode != Mode.INSERT) return;
        if (showingSuggestions) closeSuggestions();
        int[] pos = ed().deleteForward(cursorRow, cursorCol);
        cursorRow = pos[0];
        cursorCol = pos[1];
    }

    public static void handleEnter() {
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

    public static void handleEscape() {
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

    public static void handleArrow(int rowDelta, int colDelta) {
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

    public static void handleHome() {
        if (showingSuggestions) closeSuggestions();
        if (currentMode == Mode.NORMAL || currentMode == Mode.INSERT) cursorCol = 0;
    }

    public static void handleEnd() {
        if (showingSuggestions) closeSuggestions();
        if (currentMode == Mode.NORMAL || currentMode == Mode.INSERT) cursorCol = ed().getLineLength(cursorRow);
    }

    public static void handlePage(int direction) {
        if (currentMode == Mode.COMMAND) return;
        if (showingSuggestions) closeSuggestions();
        int step = Math.max(1, termRows - 4);
        cursorRow += direction * step;
        cursorCol = goalCol;
        keepGoal = true;
    }
}
