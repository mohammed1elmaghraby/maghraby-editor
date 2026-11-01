package org.maghraby.syntax;

import java.util.Set;

/**
 * Syntax highlighter for Python source code using ANSI escape codes.
 *
 * Highlights keywords, builtin functions and types, None/True/False, self/cls, decorators, names
 * being defined (def foo, class Foo), strings with string prefixes (f, r, b, u), coloured escapes and
 * f-string {interpolations}, triple-quoted docstrings across lines, numbers and comments.
 */
public class PythonHighlighter implements SyntaxHighlighter {

    private static final Set<String> KEYWORDS = Set.of(
            "and", "as", "assert", "async", "await", "break", "class", "continue",
            "def", "del", "elif", "else", "except", "finally", "for",
            "from", "global", "if", "import", "in", "is", "lambda",
            "nonlocal", "not", "or", "pass", "raise", "return", "try",
            "while", "with", "yield", "match", "case");

    private static final Set<String> CONSTANTS = Set.of("None", "True", "False", "Ellipsis", "NotImplemented");

    private static final Set<String> SELF = Set.of("self", "cls");

    private static final Set<String> BUILTIN_TYPES = Set.of(
            "int", "str", "float", "list", "dict", "set", "tuple", "bool", "bytes", "bytearray",
            "frozenset", "complex", "object", "type", "range");

    private static final Set<String> BUILTINS = Set.of(
            "print", "len", "super", "isinstance", "issubclass", "open", "enumerate", "zip", "map", "filter",
            "sorted", "sum", "min", "max", "abs", "any", "all", "input", "repr", "id", "hash", "iter", "next",
            "reversed", "round", "format", "getattr", "setattr", "hasattr", "delattr", "callable", "vars",
            "dir", "chr", "ord", "hex", "bin", "oct", "divmod", "pow", "slice", "property", "staticmethod",
            "classmethod", "exec", "eval", "compile", "globals", "locals", "breakpoint");

    @Override
    public boolean supportsExtension(String extension) {
        return "py".equalsIgnoreCase(extension) || "pyw".equalsIgnoreCase(extension);
    }

    @Override
    public HighlightResult highlightLine(String line, HighlightState state) {
        Emitter em = new Emitter(line.length());
        int n = line.length();
        int i = 0;
        HighlightState next = HighlightState.NORMAL;
        boolean afterDef = false;      // next identifier is a function name
        boolean afterClass = false;    // next identifier is a class name

        // ---- continuation of a multi-line docstring ----
        if (state == HighlightState.PY_TRIPLE_DOUBLE || state == HighlightState.PY_TRIPLE_SINGLE) {
            String closing = state == HighlightState.PY_TRIPLE_DOUBLE ? "\"\"\"" : "'''";
            int end = findClosing(line, closing, 0);
            if (end == -1) {
                em.put(Theme.DOC, line);
                return new HighlightResult(em.finish(), state);
            }
            em.put(Theme.DOC, line.substring(0, end + 3));
            i = end + 3;
        }

        while (i < n) {
            char c = line.charAt(i);

            // Comment (and shebang on the first line)
            if (c == '#') {
                em.put(i == 0 && line.startsWith("#!") ? Theme.META : Theme.COMMENT, line.substring(i));
                i = n;
                continue;
            }

            // String literal, possibly with a prefix such as f"", rb'' or u"".
            int prefixLen = stringPrefixLength(line, i);
            if (prefixLen >= 0) {
                int q = i + prefixLen;
                char quote = line.charAt(q);
                String prefix = line.substring(i, q).toLowerCase();
                boolean raw = prefix.indexOf('r') >= 0;
                boolean fstring = prefix.indexOf('f') >= 0;

                String triple = "" + quote + quote + quote;
                if (line.startsWith(triple, q)) {
                    int end = findClosing(line, triple, q + 3);
                    if (end == -1) {
                        em.put(Theme.DOC, line.substring(i));
                        next = quote == '"' ? HighlightState.PY_TRIPLE_DOUBLE : HighlightState.PY_TRIPLE_SINGLE;
                        i = n;
                    } else {
                        em.put(Theme.DOC, line.substring(i, end + 3));
                        i = end + 3;
                    }
                } else {
                    int end = q + 1;
                    boolean escaped = false;
                    while (end < n) {
                        char ch = line.charAt(end++);
                        if (escaped) escaped = false;
                        else if (ch == '\\') escaped = true;
                        else if (ch == quote) break;
                    }
                    em.put(Theme.STRING, formatString(line.substring(i, end), fstring, raw));
                    i = end;
                }
                afterDef = false;
                afterClass = false;
                continue;
            }

            // Decorator: only at the start of a line (otherwise '@' is the matrix-multiply operator)
            if (c == '@' && onlyBlanksBefore(line, i)) {
                int end = i + 1;
                while (end < n && (Character.isLetterOrDigit(line.charAt(end)) || line.charAt(end) == '_'
                        || line.charAt(end) == '.')) end++;
                em.put(Theme.META, line.substring(i, end));
                i = end;
                continue;
            }

            // Numbers
            if (Character.isDigit(c) || (c == '.' && i + 1 < n && Character.isDigit(line.charAt(i + 1)))) {
                int end = scanNumber(line, i);
                em.put(Theme.NUMBER, line.substring(i, end));
                i = end;
                continue;
            }

            // Identifiers, keywords, builtins and calls
            if (Character.isLetter(c) || c == '_') {
                int end = i + 1;
                while (end < n && (Character.isLetterOrDigit(line.charAt(end)) || line.charAt(end) == '_')) end++;
                String word = line.substring(i, end);

                String color;
                if (afterDef) {
                    color = Theme.DEFINITION;
                    afterDef = false;
                } else if (afterClass) {
                    color = Theme.DEFINITION;
                    afterClass = false;
                } else if (KEYWORDS.contains(word)) {
                    color = Theme.KEYWORD;
                    afterDef = word.equals("def");
                    afterClass = word.equals("class");
                } else if (CONSTANTS.contains(word)) {
                    color = Theme.CONSTANT;
                } else if (SELF.contains(word)) {
                    color = Theme.VARIABLE;
                } else if (BUILTIN_TYPES.contains(word)) {
                    color = Theme.TYPE;
                } else if (BUILTINS.contains(word)) {
                    color = Theme.BUILTIN;
                } else if (isAllCaps(word)) {
                    color = Theme.CONSTANT;
                } else if (Character.isUpperCase(word.charAt(0))) {
                    color = Theme.TYPE;
                } else if (nextNonBlank(line, end) == '(') {
                    color = Theme.FUNCTION;
                } else {
                    color = Theme.PLAIN;
                }
                em.put(color, word);
                i = end;
                continue;
            }

            // Operators
            if ("=+-*/%&|^!<>~@:".indexOf(c) != -1) {
                em.put(Theme.OPERATOR, String.valueOf(c));
                i++;
                continue;
            }

            // Delimiters and whitespace
            em.put(Theme.PLAIN, String.valueOf(c));
            i++;
        }

        return new HighlightResult(em.finish(), next);
    }

    /**
     * Length of the string prefix at {@code i} (0 for a bare quote), or -1 when no string literal starts here.
     * Prefixes are one or two of f, r, b, u in any case, directly followed by a quote.
     */
    private static int stringPrefixLength(String line, int i) {
        int n = line.length();
        char c = line.charAt(i);
        if (c == '"' || c == '\'') return 0;
        if (i > 0 && (Character.isLetterOrDigit(line.charAt(i - 1)) || line.charAt(i - 1) == '_')) return -1;
        int k = i;
        while (k < n && k - i < 2 && "fFrRbBuU".indexOf(line.charAt(k)) >= 0) k++;
        if (k > i && k < n && (line.charAt(k) == '"' || line.charAt(k) == '\'')) return k - i;
        return -1;
    }

    /** Escape and interpolation colouring for a one-line string token; the result ends in STRING. */
    private static String formatString(String token, boolean fstring, boolean raw) {
        if (raw && !fstring) return token;
        StringBuilder sb = new StringBuilder(token.length() + 16);
        int n = token.length();
        for (int i = 0; i < n; i++) {
            char c = token.charAt(i);
            if (c == '\\' && !raw && i + 1 < n) {
                sb.append(Theme.ESCAPE).append(c).append(token.charAt(i + 1)).append(Theme.STRING);
                i++;
            } else if (fstring && c == '{') {
                if (i + 1 < n && token.charAt(i + 1) == '{') {       // {{ literal brace
                    sb.append("{{");
                    i++;
                    continue;
                }
                int depth = 1;
                int e = i + 1;
                while (e < n && depth > 0) {
                    char d = token.charAt(e);
                    if (d == '{') depth++;
                    else if (d == '}') depth--;
                    e++;
                }
                if (depth == 0) {
                    sb.append(Theme.ESCAPE).append(token, i, e).append(Theme.STRING);
                    i = e - 1;
                } else {
                    sb.append(c);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static int findClosing(String line, String closing, int from) {
        int i = from;
        while (i < line.length()) {
            if (line.charAt(i) == '\\') { i += 2; continue; }
            if (line.startsWith(closing, i)) return i;
            i++;
        }
        return -1;
    }

    private static int scanNumber(String line, int i) {
        int n = line.length();
        int end = i;
        if (line.charAt(i) == '0' && i + 1 < n && "xXbBoO".indexOf(line.charAt(i + 1)) >= 0) {
            end = i + 2;
            while (end < n && (Character.isLetterOrDigit(line.charAt(end)) || line.charAt(end) == '_')) end++;
            return end;
        }
        while (end < n && (Character.isDigit(line.charAt(end)) || line.charAt(end) == '_')) end++;
        if (end < n && line.charAt(end) == '.') {
            end++;
            while (end < n && (Character.isDigit(line.charAt(end)) || line.charAt(end) == '_')) end++;
        }
        if (end < n && (line.charAt(end) == 'e' || line.charAt(end) == 'E')) {
            int p = end + 1;
            if (p < n && (line.charAt(p) == '+' || line.charAt(p) == '-')) p++;
            if (p < n && Character.isDigit(line.charAt(p))) {
                while (p < n && Character.isDigit(line.charAt(p))) p++;
                end = p;
            }
        }
        if (end < n && (line.charAt(end) == 'j' || line.charAt(end) == 'J')) end++;
        return Math.max(end, i + 1);
    }

    private static boolean onlyBlanksBefore(String line, int i) {
        for (int k = 0; k < i; k++) if (!Character.isWhitespace(line.charAt(k))) return false;
        return true;
    }

    private static boolean isAllCaps(String w) {
        if (w.length() < 2) return false;
        boolean letter = false;
        for (int i = 0; i < w.length(); i++) {
            char ch = w.charAt(i);
            if (ch >= 'A' && ch <= 'Z') letter = true;
            else if (!(ch == '_' || (ch >= '0' && ch <= '9'))) return false;
        }
        return letter;
    }

    private static char nextNonBlank(String line, int from) {
        int p = from;
        while (p < line.length() && Character.isWhitespace(line.charAt(p))) p++;
        return p < line.length() ? line.charAt(p) : '\0';
    }
}
