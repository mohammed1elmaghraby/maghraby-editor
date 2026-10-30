package org.maghraby.ui;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Nerd Font glyphs for the file tree. Needs a Nerd Font in the terminal (https://www.nerdfonts.com),
 * otherwise the glyphs show up as boxes: turn them off with {@code :set noicons} or by starting the
 * editor with {@code MAGHRABY_ICONS=0}.
 *
 * Only glyphs from the Basic Multilingual Plane are used (Seti-UI, Devicons, Font Awesome), so every
 * icon is exactly one Java {@code char} and the tree's width arithmetic (String.length) stays correct.
 * Code points are written as escapes because private-use characters are invisible in most editors.
 */
public final class FileIcons {
    private FileIcons() {}

    /** A glyph plus the 256-colour foreground it is drawn in. */
    public static final class Icon {
        public final char glyph;
        public final int color;

        Icon(char glyph, int color) {
            this.glyph = glyph;
            this.color = color;
        }
    }

    private static final Icon FOLDER = new Icon('', 110);
    private static final Icon FOLDER_OPEN = new Icon('', 110);
    private static final Icon FILE = new Icon('', 252);
    private static final Icon TEXT = new Icon('', 252);

    private static final Map<String, Icon> BY_NAME = new HashMap<>();
    private static final Map<String, Icon> BY_EXT = new HashMap<>();

    static {
        // --- exact file names (lower-case) ---
        BY_NAME.put("dockerfile", new Icon('', 39));
        BY_NAME.put(".gitignore", new Icon('', 202));
        BY_NAME.put(".gitattributes", new Icon('', 202));
        BY_NAME.put(".gitmodules", new Icon('', 202));
        BY_NAME.put("makefile", new Icon('', 245));
        BY_NAME.put("pom.xml", new Icon('', 173));
        BY_NAME.put("license", new Icon('', 221));
        BY_NAME.put("readme.md", new Icon('', 110));

        // --- languages ---
        Icon java = new Icon('', 173);
        BY_EXT.put("java", java);
        BY_EXT.put("jar", java);
        BY_EXT.put("class", java);
        Icon python = new Icon('', 75);
        BY_EXT.put("py", python);
        BY_EXT.put("pyw", python);
        Icon c = new Icon('', 68);
        BY_EXT.put("c", c);
        BY_EXT.put("h", c);
        Icon cpp = new Icon('', 68);
        BY_EXT.put("cpp", cpp);
        BY_EXT.put("cc", cpp);
        BY_EXT.put("cxx", cpp);
        BY_EXT.put("hpp", cpp);
        BY_EXT.put("hh", cpp);
        Icon js = new Icon('', 221);
        BY_EXT.put("js", js);
        BY_EXT.put("mjs", js);
        BY_EXT.put("cjs", js);
        BY_EXT.put("jsx", js);
        Icon ts = new Icon('', 74);
        BY_EXT.put("ts", ts);
        BY_EXT.put("tsx", ts);
        BY_EXT.put("go", new Icon('', 81));
        BY_EXT.put("rs", new Icon('', 208));
        BY_EXT.put("rb", new Icon('', 160));
        BY_EXT.put("php", new Icon('', 104));
        BY_EXT.put("lua", new Icon('', 75));
        BY_EXT.put("sql", new Icon('', 252));

        // --- web / markup / data ---
        Icon html = new Icon('', 202);
        BY_EXT.put("html", html);
        BY_EXT.put("htm", html);
        Icon css = new Icon('', 39);
        BY_EXT.put("css", css);
        BY_EXT.put("scss", css);
        BY_EXT.put("sass", css);
        BY_EXT.put("json", new Icon('', 179));
        Icon md = new Icon('', 110);
        BY_EXT.put("md", md);
        BY_EXT.put("markdown", md);
        Icon xml = new Icon('', 173);
        BY_EXT.put("xml", xml);
        BY_EXT.put("svg", xml);
        Icon cfg = new Icon('', 245);
        BY_EXT.put("yml", cfg);
        BY_EXT.put("yaml", cfg);
        BY_EXT.put("toml", cfg);
        BY_EXT.put("ini", cfg);
        BY_EXT.put("conf", cfg);
        BY_EXT.put("properties", cfg);

        // --- shell ---
        Icon sh = new Icon('', 114);
        BY_EXT.put("sh", sh);
        BY_EXT.put("bash", sh);
        BY_EXT.put("zsh", sh);

        // --- everything else ---
        BY_EXT.put("txt", TEXT);
        BY_EXT.put("log", TEXT);
        Icon image = new Icon('', 139);
        BY_EXT.put("png", image);
        BY_EXT.put("jpg", image);
        BY_EXT.put("jpeg", image);
        BY_EXT.put("gif", image);
        BY_EXT.put("webp", image);
        BY_EXT.put("ico", image);
        BY_EXT.put("pdf", new Icon('', 160));
        Icon archive = new Icon('', 179);
        BY_EXT.put("zip", archive);
        BY_EXT.put("tar", archive);
        BY_EXT.put("gz", archive);
        BY_EXT.put("tgz", archive);
        BY_EXT.put("7z", archive);
        BY_EXT.put("lock", new Icon('', 245));
    }

    /** Picks the icon for a tree row. Never returns null. */
    public static Icon forEntry(String fileName, boolean dir, boolean expanded) {
        if (dir) return expanded ? FOLDER_OPEN : FOLDER;
        String lower = fileName.toLowerCase(Locale.ROOT);
        Icon byName = BY_NAME.get(lower);
        if (byName != null) return byName;
        int dot = lower.lastIndexOf('.');
        if (dot >= 0 && dot < lower.length() - 1) {
            Icon byExt = BY_EXT.get(lower.substring(dot + 1));
            if (byExt != null) return byExt;
        }
        return FILE;
    }

    /** Default for {@code State.nerdIcons}: on, unless MAGHRABY_ICONS is 0 / false / off / no. */
    public static boolean enabledByEnvironment() {
        String v = System.getenv("MAGHRABY_ICONS");
        if (v == null) return true;
        v = v.trim().toLowerCase(Locale.ROOT);
        return !(v.equals("0") || v.equals("false") || v.equals("off") || v.equals("no"));
    }
}
