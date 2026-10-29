package org.maghraby.ui;

import org.maghraby.core.Mode;
import org.maghraby.input.Autocomplete;
import org.maghraby.input.Commands;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.maghraby.core.State.*;
import static org.maghraby.ui.Ansi.*;
import static org.maghraby.ui.Terminal.termCols;

/**
 * File tree on the left of the screen (like neovim's nvim-tree).
 *
 * Directories are read lazily: only folders you have expanded are listed, and the flattened list of
 * visible rows is rebuilt only when you expand, collapse or refresh, never on every frame. The input
 * thread owns all state changes; the render thread only reads the immutable `visible` snapshot.
 *
 * Keys while the tree has focus: j/k or arrows move, Enter/o/Space open a file or fold a folder,
 * l/Right expand, h/Left collapse or jump to the parent, g/G top/bottom, H toggle dotfiles,
 * R refresh, q close, Tab/Esc back to the editor, ':' still starts a command.
 */
public final class Sidebar {
    private Sidebar() {}

    public static final int WIDTH = 28;                 // columns used by the tree itself
    public static final int MIN_TERMINAL_COLS = 60;     // narrower terminals hide the tree automatically
    static final int MAX_ENTRIES_PER_DIR = 2000;        // a huge folder must not freeze the editor

    public static final String HINT =
            "TREE  j/k move  Enter open  h/l fold  H dotfiles  R refresh  q close  Tab back";

    /** One visible row. Immutable, so the render thread can read a snapshot without locking. */
    private static final class Entry {
        final Path path;
        final boolean dir;
        final boolean expanded;
        final boolean more;     // synthetic "… more files not shown" row
        final int depth;

        Entry(Path path, boolean dir, boolean expanded, int depth, boolean more) {
            this.path = path;
            this.dir = dir;
            this.expanded = expanded;
            this.depth = depth;
            this.more = more;
        }
    }

    private static volatile boolean open = false;
    private static volatile boolean focused = false;
    private static volatile boolean showHidden = false;
    private static volatile Path root = null;
    private static volatile List<Entry> visible = Collections.emptyList();
    private static volatile int selected = 0;
    private static volatile int scrollTop = 0;          // written by the render thread only
    private static volatile int rows = 10;              // rows drawn last time (for PageUp/PageDown)
    private static final Set<Path> expanded = new HashSet<>(); // input thread only

    // ------------------------------------------------------------------
    // State queries
    // ------------------------------------------------------------------

    public static boolean isOpen() {
        return open;
    }

    /** True when the tree is open AND the terminal is wide enough to actually show it. */
    public static boolean isVisible() {
        return open && termCols >= MIN_TERMINAL_COLS;
    }

    public static boolean isFocused() {
        return focused && isVisible();
    }

    /** Columns the tree takes (0 when hidden); the renderer also reserves one column for the separator. */
    public static int width(int cols) {
        return open && cols >= MIN_TERMINAL_COLS ? WIDTH : 0;
    }

    // ------------------------------------------------------------------
    // Opening, closing, focus
    // ------------------------------------------------------------------

    /** Ctrl+B / :tree. */
    public static void toggle() {
        if (open) close();
        else show(null);
    }

    /** Opens the tree with the keyboard focus. {@code dir == null} means the project root. */
    public static void show(Path dir) {
        if (termCols < MIN_TERMINAL_COLS) {
            statusMessage = "Terminal too narrow for the file tree (needs " + MIN_TERMINAL_COLS + " columns).";
            return;
        }
        Path r = dir != null ? dir : defaultRoot();
        if (!r.equals(root)) {
            root = r;
            expanded.clear();
            selected = 0;
            scrollTop = 0;
        }
        open = true;
        giveFocus();
        rebuild();
        reveal(org.maghraby.ui.Panes.normPath(D().filename));
        forceClear.set(true);
        needsRedraw = true;
    }

    public static void close() {
        open = false;
        focused = false;
        forceClear.set(true);
        needsRedraw = true;
    }

    /** Moves the keyboard focus into the tree (Tab cycle, Ctrl+W). */
    public static void focusTree() {
        if (!open) {
            show(null);
            return;
        }
        giveFocus();
        needsRedraw = true;
    }

    public static void blur() {
        focused = false;
        needsRedraw = true;
    }

    private static void giveFocus() {
        focused = true;
        currentMode = Mode.NORMAL;
        if (showingSuggestions) Autocomplete.closeSuggestions();
    }

    /** The git root of the open file, otherwise the working directory. */
    private static Path defaultRoot() {
        Path file = org.maghraby.ui.Panes.normPath(D().filename);
        for (Path p = file == null ? null : file.getParent(); p != null; p = p.getParent()) {
            if (Files.exists(p.resolve(".git"))) return p;
        }
        return Paths.get("").toAbsolutePath().normalize();
    }

    // ------------------------------------------------------------------
    // Keys
    // ------------------------------------------------------------------

    /** A printable key typed while the tree has focus in Normal mode (':' is handled by the caller). */
    public static void handleKey(String s) {
        switch (s) {
            case "j": move(1); break;
            case "k": move(-1); break;
            case "h": collapseOrParent(); break;
            case "l": expandOrOpen(); break;
            case "g": selected = 0; break;
            case "G": selected = Math.max(0, visible.size() - 1); break;
            case "R": rebuild(); break;
            case "H": showHidden = !showHidden; rebuild(); break;
            case "q": close(); break;
            case " ": case "o": activate(); break;
            default: break;
        }
    }

    public static void arrow(int rowDelta, int colDelta) {
        if (rowDelta != 0) move(rowDelta);
        else if (colDelta < 0) collapseOrParent();
        else if (colDelta > 0) expandOrOpen();
    }

    public static void pageMove(int direction) {
        move(direction * Math.max(1, rows - 1));
    }

    private static void move(int delta) {
        int n = visible.size();
        selected = Math.max(0, Math.min(selected + delta, n - 1));
    }

    /** Enter: fold/unfold a folder, or open a file in the focused editor pane. */
    public static void activate() {
        Entry e = current();
        if (e == null || e.more) return;
        if (e.dir) {
            if (!expanded.add(e.path)) expanded.remove(e.path);
            rebuild();
        } else if (Commands.openFile(e.path.toString(), false)) {
            blur();
        }
    }

    private static void collapseOrParent() {
        Entry e = current();
        if (e == null) return;
        if (e.dir && e.expanded) {
            expanded.remove(e.path);
            rebuild();
            return;
        }
        List<Entry> list = visible;
        for (int i = selected - 1; i >= 0; i--) {
            if (list.get(i).depth < e.depth) {
                selected = i;
                return;
            }
        }
    }

    private static void expandOrOpen() {
        Entry e = current();
        if (e == null || e.more) return;
        if (!e.dir) {
            activate();
        } else if (!e.expanded) {
            expanded.add(e.path);
            rebuild();
        } else if (selected + 1 < visible.size() && visible.get(selected + 1).depth > e.depth) {
            selected++; // already open: step into the first child
        }
    }

    private static Entry current() {
        List<Entry> list = visible;
        int s = selected;
        return s >= 0 && s < list.size() ? list.get(s) : null;
    }

    private static Path selectedPath() {
        Entry e = current();
        return e == null || e.more ? null : e.path;
    }

    // ------------------------------------------------------------------
    // The row list
    // ------------------------------------------------------------------

    private static final class Kid {
        final Path path;
        final boolean dir;
        final String key;

        Kid(Path path) {
            this.path = path;
            this.dir = Files.isDirectory(path);
            this.key = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /** Re-reads the expanded folders and rebuilds the visible rows, keeping the selection on the same path. */
    private static void rebuild() {
        Path keep = selectedPath();
        List<Entry> out = new ArrayList<>();
        Path r = root;
        if (r != null) addChildren(out, r, 0);
        visible = out;

        int idx = -1;
        if (keep != null) {
            for (int i = 0; i < out.size(); i++) {
                if (!out.get(i).more && keep.equals(out.get(i).path)) {
                    idx = i;
                    break;
                }
            }
        }
        selected = idx >= 0 ? idx : Math.max(0, Math.min(selected, out.size() - 1));
        needsRedraw = true;
    }

    private static void addChildren(List<Entry> out, Path dir, int depth) {
        List<Kid> kids = new ArrayList<>();
        boolean truncated = false;
        try (Stream<Path> s = Files.list(dir)) {
            Iterator<Path> it = s.iterator();
            while (it.hasNext()) {
                Path p = it.next();
                if (p.getFileName() == null) continue;
                if (!showHidden && p.getFileName().toString().startsWith(".")) continue;
                if (kids.size() >= MAX_ENTRIES_PER_DIR) {
                    truncated = true;
                    break;
                }
                kids.add(new Kid(p));
            }
        } catch (IOException | RuntimeException e) {
            return; // unreadable folder: show it empty
        }
        kids.sort((a, b) -> {
            if (a.dir != b.dir) return a.dir ? -1 : 1; // folders first
            return a.key.compareTo(b.key);
        });
        for (Kid k : kids) {
            boolean isOpen = k.dir && expanded.contains(k.path);
            out.add(new Entry(k.path, k.dir, isOpen, depth, false));
            if (isOpen) addChildren(out, k.path, depth + 1);
        }
        if (truncated) out.add(new Entry(dir, false, false, depth, true));
    }

    /** Expands the folders above {@code file} and selects it, when it lives under the tree's root. */
    private static void reveal(Path file) {
        Path r = root;
        if (file == null || r == null || !file.startsWith(r) || file.equals(r)) return;
        for (Path p = file.getParent(); p != null && !p.equals(r); p = p.getParent()) expanded.add(p);
        rebuild();
        List<Entry> list = visible;
        for (int i = 0; i < list.size(); i++) {
            if (!list.get(i).more && file.equals(list.get(i).path)) {
                selected = i;
                return;
            }
        }
    }

    // ------------------------------------------------------------------
    // Drawing (render thread)
    // ------------------------------------------------------------------

    /**
     * Draws the tree in columns [x, x + w) of rows [y, y + h), plus a separator line in column x + w.
     * {@code currentFile} (normalised, may be null) is highlighted as the file being edited.
     */
    public static void draw(StringBuilder sb, int x, int y, int w, int h, Path currentFile) {
        if (w < 4 || h < 2) return;
        boolean foc = isFocused();
        Path r = root;
        String title = r == null ? "" : (r.getFileName() == null ? r.toString() : r.getFileName().toString());

        String head = " " + clip(sanitize(title), w - 1);
        sb.append(ESC).append('[').append(y).append(';').append(x).append('H')
                .append(foc ? bg(24) : bg(237)).append(foc ? fg(231) : fg(252)).append(BOLD)
                .append(head).append(spaces(w - head.length())).append(ANSI_RESET);

        List<Entry> list = visible;
        int sel = selected;
        int shown = h - 1;
        rows = shown;

        int top = scrollTop;
        if (sel < top) top = sel;
        else if (sel >= top + shown) top = sel - shown + 1;
        top = Math.max(0, Math.min(top, Math.max(0, list.size() - shown)));
        scrollTop = top;

        for (int i = 0; i < shown; i++) {
            sb.append(ESC).append('[').append(y + 1 + i).append(';').append(x).append('H');
            int idx = top + i;
            if (list.isEmpty() && i == 0) {
                sb.append(fg(240)).append(" (empty)").append(spaces(w - 8)).append(ANSI_RESET);
                continue;
            }
            if (idx >= list.size()) {
                sb.append(spaces(w));
                continue;
            }
            Entry e = list.get(idx);
            String icon = e.more ? "  " : e.dir ? (e.expanded ? "▾ " : "▸ ") : "  ";
            String name = e.more ? "… more files not shown" : sanitize(e.path.getFileName().toString()) + (e.dir ? "/" : "");
            String text = clip(" " + spaces(2 * e.depth) + icon + name, w);

            String style;
            if (idx == sel) style = (foc ? bg(24) : bg(238)) + fg(231);
            else if (e.more) style = fg(240);
            else if (!e.dir && currentFile != null && currentFile.equals(e.path)) style = fg(221) + BOLD;
            else if (e.dir) style = fg(110);
            else style = fg(252);
            sb.append(style).append(text).append(spaces(w - text.length())).append(ANSI_RESET);
        }

        sb.append(fg(238));
        for (int i = 0; i < h; i++) {
            sb.append(ESC).append('[').append(y + i).append(';').append(x + w).append('H').append('│');
        }
        sb.append(ANSI_RESET);
    }
}