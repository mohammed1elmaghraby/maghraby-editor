package org.maghraby.ui;

import org.maghraby.ui.Split;
import org.maghraby.core.Doc;
import org.maghraby.core.Line;
import org.maghraby.core.Mode;
import org.maghraby.core.TextEditor;
import org.maghraby.lsp.LspClient;
import org.maghraby.lsp.LspDocumentSync;
import org.maghraby.syntax.HighlightState;
import org.maghraby.syntax.SyntaxHighlighter.HighlightResult;
import org.maghraby.syntax.SyntaxHighlighter;

import java.util.List;

import static org.maghraby.core.State.*;
import static org.maghraby.ui.Ansi.*;
import static org.maghraby.ui.Terminal.*;

/**
 * Render thread and frame builder: tab line, panes, status line, completion popup.
 */
public final class Renderer {
    private Renderer() {}

    public static int lastClockMinute = -1;    // Render thread only
    public static volatile boolean statusFadePending = false; // a status message is about to fade

    public static final String[] SPINNER = {
            "⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"
    };

    public static final long STATUS_TTL_MS = 5000; // a status message fades back to the mode hint

    // Render thread only: when the current status message first appeared.
    public static String lastStatusSeen = "";

    public static long statusSince = System.currentTimeMillis();

    /** Right-hand header badge for the language server: {coloured, plain} (plain is used to measure width). */
    public static String[] lspBadge() {
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
    public static LspClient.Diagnostic bestDiagnosticAt(List<LspClient.Diagnostic> diags, int row) {
        LspClient.Diagnostic best = null;
        for (LspClient.Diagnostic d : diags) {
            // An end position at column 0 of the next line is exclusive: it doesn't touch that line.
            int last = (d.endCol == 0 && d.endLine > d.line) ? d.endLine - 1 : d.endLine;
            if (row < d.line || row > last) continue;
            if (best == null || d.severity < best.severity) best = d;
        }
        return best;
    }

    public static int severityColor(int severity) {
        return severity == 1 ? 203 : severity == 2 ? 214 : severity == 3 ? 81 : 245;
    }

    public static void renderLoop() {
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

    public static long lastAnimTick = 0;

    public static boolean lspStarting() {
        LspDocumentSync s = D().lsp;
        return s != null && !s.client().ready().isDone();
    }

    public static String modeHint(Mode mode) {
        switch (mode) {
            case INSERT: return "-- INSERT --  Esc:normal  Tab:accept/indent  ↑↓:choose suggestion";
            case COMMAND: return HELP;
            default: return DEFAULT_STATUS;
        }
    }

    public static int modeColor(Mode mode) {
        switch (mode) {
            case INSERT: return 78;   // green
            case COMMAND: return 215; // orange
            default: return 75;       // blue
        }
    }

    /** Foreground colour of a status message, chosen by what it says. */
    public static String statusFg(String msg) {
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

    public static char kindLetter(int kind) {
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

    public static int kindColor(int kind) {
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

    public static int[] diagCounts() {
        int errors = 0, warnings = 0;
        for (LspClient.Diagnostic d : D().diagnostics) {
            if (d.severity == 1) errors++;
            else if (d.severity == 2) warnings++;
        }
        return new int[]{errors, warnings};
    }

    /** Right-hand side of the status line. `full` adds diagnostics and the scroll percentage. */
    public static Line statusRight(boolean full, int[] dc, int pct, String pos, String clock) {
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
    public static int[] drawPane(StringBuilder sb, int idx, Doc doc, int x, int y, int w, int h, int row, int col,
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

    public static String buildFrame() {
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
}
