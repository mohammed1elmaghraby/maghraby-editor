package org.maghraby.ui;

import static org.maghraby.core.State.*;

/**
 * ANSI escape codes and string helpers for drawing on the terminal (colours, slicing, tabs, sanitising).
 */
public final class Ansi {
    private Ansi() {}

    public static final String ESC = "\033";
    public static final String ANSI_HIDE_CURSOR = ESC + "[?25l";
    public static final String ANSI_SHOW_CURSOR = ESC + "[?25h";
    public static final String ANSI_RESET = ESC + "[0m";
    public static final String ANSI_CLEAR_SCREEN = ESC + "[2J";
    public static final String ANSI_CLEAR_LINE = ESC + "[2K";
    public static final String ANSI_ENTER_ALT_SCREEN = ESC + "[?1049h";
    public static final String ANSI_LEAVE_ALT_SCREEN = ESC + "[?1049l";

    // --- Colour helpers (256-colour palette) ---
    public static final String BOLD = ESC + "[1m";

    public static final String BOLD_OFF = ESC + "[22m";
    public static final String FG_DEFAULT = ESC + "[39m";

    public static String fg(int n) { return ESC + "[38;5;" + n + "m"; }

    public static String bg(int n) { return ESC + "[48;5;" + n + "m"; }

    public static String spaces(int n) {
        if (n <= 0) return "";
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) sb.append(' ');
        return sb.toString();
    }

    /** Lays out one full-width bar: coloured left text, coloured right text, filler in between. */
    public static String bar(String left, int leftLen, String right, int rightLen, int cols, String barBg) {
        StringBuilder sb = new StringBuilder(barBg);
        sb.append(left);
        if (leftLen + rightLen < cols) {
            sb.append(barBg).append(spaces(cols - leftLen - rightLen)).append(right);
        } else if (leftLen < cols) {
            sb.append(barBg).append(spaces(cols - leftLen)); // not enough room for the right side
        }
        return sb.append(ANSI_RESET).toString();
    }

    public static String clip(String s, int max) {
        if (max <= 0) return "";
        return s.length() <= max ? s : s.substring(0, Math.max(0, max - 1)) + "…";
    }

    public static String repeat(String unit, int n) {
        StringBuilder sb = new StringBuilder(Math.max(0, n) * unit.length());
        for (int i = 0; i < n; i++) sb.append(unit);
        return sb.toString();
    }

    public static String baseName(String path) {
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    public static boolean isControl(char c) {
        return c < 0x20 || (c >= 0x7F && c < 0xA0);
    }

    /** Prevents file contents from injecting terminal escape sequences. */
    public static String sanitize(String s) {
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
    public static String expandTabsAndSanitize(String line) {
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
    public static int visualCol(String line, int col) {
        int v = 0;
        int end = Math.min(col, line.length());
        for (int i = 0; i < end; i++) {
            v += line.charAt(i) == '\t' ? TAB_WIDTH - (v % TAB_WIDTH) : 1;
        }
        return v;
    }

    /** Slices an ANSI-colored string to fit the viewport horizontally. */
    public static String sliceAnsi(String s, int start, int width) {
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
}
