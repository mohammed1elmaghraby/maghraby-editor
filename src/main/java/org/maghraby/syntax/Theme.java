package org.maghraby.syntax;

/**
 * One shared colour theme (xterm 256-colour palette, roughly "One Dark") for every highlighter,
 * so languages look consistent and a colour can be changed in a single place.
 *
 * Each constant is a complete SGR sequence that fully sets the style (colour plus italic/bold on or
 * off), so highlighters only need to emit the next token's style; they never have to undo the previous one.
 */
final class Theme {
    private Theme() {}

    static final String RESET = "\033[0m";

    private static String fg(int n) { return "\033[0;38;5;" + n + "m"; }
    private static String fgItalic(int n) { return "\033[0;3;38;5;" + n + "m"; }
    private static String fgBold(int n) { return "\033[0;1;38;5;" + n + "m"; }

    /** Control flow and declarations: if, for, return, class... */
    static final String KEYWORD = fgBold(176);   // purple
    /** Types, classes, annotations' targets. */
    static final String TYPE = fg(180);          // gold
    /** Built-in functions and shell builtins (print, len, echo, cd...). */
    static final String BUILTIN = fgBold(75);    // bright blue
    /** Function / command names and calls. */
    static final String FUNCTION = fg(75);       // blue
    /** Names being defined (def foo, class Foo, function bar). */
    static final String DEFINITION = fgBold(114); // bright green
    /** String literals. */
    static final String STRING = fg(114);        // green
    /** Escape sequences and interpolation inside strings. */
    static final String ESCAPE = fgBold(80);     // teal
    /** Numeric literals. */
    static final String NUMBER = fg(209);        // orange
    /** true / false / null / None and similar. */
    static final String CONSTANT = fg(209);      // orange
    /** Comments. */
    static final String COMMENT = fgItalic(244); // dim grey, italic
    /** Doc strings / documentation comments. */
    static final String DOC = fgItalic(108);     // muted green, italic
    /** Operators. */
    static final String OPERATOR = fg(73);       // cyan
    /** Variables with special meaning (this, self, $VAR). */
    static final String VARIABLE = fg(204);      // coral
    /** Annotations, decorators, preprocessor, shebang. */
    static final String META = fg(173);          // copper
    /** Command-line options such as -f or --force. */
    static final String OPTION = fg(222);        // pale yellow
    /** Brackets, commas, plain text. */
    static final String PLAIN = RESET;

    /** Colours {@code @tag} words inside a documentation comment. The result ends in DOC. */
    static String docWithTags(String token) {
        if (token.indexOf('@') < 0) return token;
        StringBuilder sb = new StringBuilder(token.length() + 16);
        int n = token.length();
        for (int i = 0; i < n; i++) {
            char c = token.charAt(i);
            if (c == '@' && i + 1 < n && Character.isLetter(token.charAt(i + 1))) {
                int e = i + 1;
                while (e < n && Character.isLetter(token.charAt(e))) e++;
                sb.append(META).append(token, i, e).append(DOC);
                i = e - 1;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * Colours the body of a quoted string: backslash escapes are highlighted inside the string colour.
     * The returned text starts without a colour code (the caller emits STRING first) and always ends
     * in STRING, so the caller's "current colour" bookkeeping stays correct.
     */
    static String stringWithEscapes(String token) {
        if (token.indexOf('\\') < 0) return token;
        StringBuilder sb = new StringBuilder(token.length() + 16);
        int n = token.length();
        for (int i = 0; i < n; i++) {
            char c = token.charAt(i);
            if (c == '\\' && i + 1 < n) {
                sb.append(ESCAPE).append(c).append(token.charAt(i + 1)).append(STRING);
                i++;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
