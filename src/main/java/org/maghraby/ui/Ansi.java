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
    /** Bracketed paste: the terminal wraps pasted text in ESC[200~ ... ESC[201~ so it is not mistaken for typing. */
    public static final String ANSI_PASTE_ON = ESC + "[?2004h";
    public static final String ANSI_PASTE_OFF = ESC + "[?2004l";

    // --- Colour helpers (256-colour palette) ---
    public static final String BOLD = ESC + "[1m";

    public static final String BOLD_OFF = ESC + "[22m";
    public static final String FG_DEFAULT = ESC + "[39m";

    public static String fg(int n) { return ESC + "[38;5;" + n + "m"; }

    public static String bg(int n) { return ESC + "[48;5;" + n + "m"; }

    // --- True colour and alpha blending ---
    // A terminal has no real transparency, so "alpha" is computed here: result = top*a + base*(1-a).

    /** True when the terminal advertises 24-bit colour (COLORTERM=truecolor|24bit); else 256 colours are used. */
    public static final boolean TRUECOLOR = detectTrueColor();

    /** Assumed editor background, the colour tints are blended onto. Override with MEDIT_BG=#rrggbb. */
    public static final int[] EDITOR_BG = parseHex(System.getenv("MEDIT_BG"), new int[]{30, 30, 30});

    private static boolean detectTrueColor() {
        String ct = System.getenv("COLORTERM");
        if (ct == null) return false;
        ct = ct.toLowerCase(java.util.Locale.ROOT);
        return ct.contains("truecolor") || ct.contains("24bit");
    }

    private static int[] parseHex(String hex, int[] fallback) {
        if (hex == null) return fallback;
        String h = hex.startsWith("#") ? hex.substring(1) : hex;
        if (h.length() != 6) return fallback;
        try {
            int v = Integer.parseInt(h, 16);
            return new int[]{(v >> 16) & 255, (v >> 8) & 255, v & 255};
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** RGB of an xterm 256-colour palette index. */
    public static int[] xtermRgb(int n) {
        if (n < 16) {
            int[][] base = {{0, 0, 0}, {205, 0, 0}, {0, 205, 0}, {205, 205, 0}, {0, 0, 238}, {205, 0, 205},
                    {0, 205, 205}, {229, 229, 229}, {127, 127, 127}, {255, 0, 0}, {0, 255, 0}, {255, 255, 0},
                    {92, 92, 255}, {255, 0, 255}, {0, 255, 255}, {255, 255, 255}};
            return base[Math.max(0, n)].clone();
        }
        if (n >= 232) {
            int g = 8 + 10 * (Math.min(n, 255) - 232);
            return new int[]{g, g, g};
        }
        int i = n - 16;
        int[] lv = {0, 95, 135, 175, 215, 255};
        return new int[]{lv[i / 36], lv[(i / 6) % 6], lv[i % 6]};
    }

    /** Alpha blend: `alpha` (0..1) of `top` over `base`. */
    public static int[] blend(int[] top, int[] base, double alpha) {
        double a = Math.max(0, Math.min(1, alpha));
        int[] r = new int[3];
        for (int i = 0; i < 3; i++) r[i] = (int) Math.round(top[i] * a + base[i] * (1 - a));
        return r;
    }

    private static int nearest256(int[] c) {
        int[] lv = {0, 95, 135, 175, 215, 255};
        int[] idx = new int[3];
        for (int k = 0; k < 3; k++) {
            int best = 0;
            for (int j = 1; j < 6; j++) if (Math.abs(lv[j] - c[k]) < Math.abs(lv[best] - c[k])) best = j;
            idx[k] = best;
        }
        int cube = 16 + 36 * idx[0] + 6 * idx[1] + idx[2];
        int gray = (c[0] + c[1] + c[2]) / 3;
        int gi = Math.max(0, Math.min(23, Math.round((gray - 8) / 10f)));
        int[] cc = xtermRgb(cube), gg = xtermRgb(232 + gi);
        return dist(c, gg) < dist(c, cc) ? 232 + gi : cube;
    }

    private static int dist(int[] a, int[] b) {
        int d = 0;
        for (int i = 0; i < 3; i++) d += (a[i] - b[i]) * (a[i] - b[i]);
        return d;
    }

    public static String rgbFg(int[] c) {
        return TRUECOLOR ? ESC + "[38;2;" + c[0] + ";" + c[1] + ";" + c[2] + "m" : fg(nearest256(c));
    }

    public static String rgbBg(int[] c) {
        return TRUECOLOR ? ESC + "[48;2;" + c[0] + ";" + c[1] + ";" + c[2] + "m" : bg(nearest256(c));
    }

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