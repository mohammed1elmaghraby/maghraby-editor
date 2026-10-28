package org.maghraby.ui;

import org.maghraby.ui.Split;
import org.maghraby.core.Doc;
import org.maghraby.core.TextEditor;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.maghraby.core.State.*;
import static org.maghraby.ui.Terminal.*;
import static org.maghraby.input.Autocomplete.*;
import static org.maghraby.lsp.LspManager.*;
import static org.maghraby.input.Commands.*;

/**
 * Split screen: opening, switching and closing the two panes.
 */
public final class Panes {
    private Panes() {}

    // ---- Split screen ----

    public static Path normPath(String name) {
        try {
            return Paths.get(name.trim()).toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** A document already open in one of the panes under this file name, or null. */
    public static Doc findOpenDoc(String name) {
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
    public static void openSplit(Split kind, String arg) {
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
    public static void switchPane() {
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
    public static boolean closePane(int idx, boolean force) {
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
}
