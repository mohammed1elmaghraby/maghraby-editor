package org.maghraby.syntax;

import java.util.Set;

/**
 * Syntax highlighter for C-family languages (Java, C, C++, C#) using ANSI escape codes.
 *
 * Highlights keywords, primitive types, class/type names, constants (true, null, MAX_SIZE), function
 * calls, names being defined (class Foo), annotations, numbers, strings with coloured escapes,
 * comments, documentation comments with their @tags, preprocessor lines with #include headers, and
 * Java text blocks. For C# it also handles verbatim (@"...") and interpolated ($"...") strings,
 * {@code ///} XML documentation comments and {@code #region}-style directives.
 */
public class CLikeHighlighter implements SyntaxHighlighter {

    private static final Set<String> PRIMITIVES = Set.of(
            "boolean", "byte", "char", "short", "int", "long", "float", "double", "void", "var",
            "auto", "bool", "signed", "unsigned", "size_t", "wchar_t");

    private static final Set<String> CONSTANTS = Set.of("true", "false", "null", "nullptr", "NULL");

    /** Extra built-in type keywords that only exist in C#. */
    private static final Set<String> CSHARP_PRIMITIVES = Set.of(
            "string", "object", "decimal", "sbyte", "ushort", "uint", "ulong", "nint", "nuint", "dynamic");

    private static final Set<String> SELF = Set.of("this", "super", "base");

    /** Keywords after which the next identifier is the name being defined. */
    private static final Set<String> DEFINERS = Set.of(
            "class", "interface", "enum", "record", "struct", "union", "namespace", "typename");

    private final Set<String> supportedExtensions;
    private final Set<String> keywords;
    private final boolean preprocessor;
    private final boolean csharp;

    public CLikeHighlighter(Set<String> supportedExtensions, Set<String> keywords) {
        this.supportedExtensions = supportedExtensions;
        this.keywords = keywords;
        this.csharp = supportedExtensions.contains("cs");
        this.preprocessor = supportedExtensions.contains("c") || supportedExtensions.contains("cpp")
                || supportedExtensions.contains("h") || csharp;
    }

    @Override
    public boolean supportsExtension(String extension) {
        return supportedExtensions.contains(extension.toLowerCase());
    }

    @Override
    public HighlightResult highlightLine(String line, HighlightState state) {
        Emitter em = new Emitter(line.length());
        int n = line.length();
        int i = 0;
        HighlightState next = HighlightState.NORMAL;
        boolean afterDefiner = false;

        // ---- continuation of a multi-line construct from a previous line ----
        if (state == HighlightState.C_BLOCK_COMMENT || state == HighlightState.C_DOC_COMMENT) {
            boolean doc = state == HighlightState.C_DOC_COMMENT;
            int end = line.indexOf("*/", 0);
            String color = doc ? Theme.DOC : Theme.COMMENT;
            if (end == -1) {
                em.put(color, doc ? Theme.docWithTags(line) : line);
                return new HighlightResult(em.finish(), state);
            }
            String part = line.substring(0, end + 2);
            em.put(color, doc ? Theme.docWithTags(part) : part);
            i = end + 2;
        } else if (state == HighlightState.C_VERBATIM) {
            int end = verbatimEnd(line, 0);
            if (end == -1) {
                em.put(Theme.STRING, line);
                return new HighlightResult(em.finish(), state);
            }
            em.put(Theme.STRING, line.substring(0, end));
            i = end;
        } else if (state == HighlightState.C_TEXT_BLOCK) {
            int end = findUnescaped(line, "\"\"\"", 0);
            if (end == -1) {
                em.put(Theme.STRING, Theme.stringWithEscapes(line));
                return new HighlightResult(em.finish(), state);
            }
            em.put(Theme.STRING, Theme.stringWithEscapes(line.substring(0, end + 3)));
            i = end + 3;
        }

        while (i < n) {
            char c = line.charAt(i);

            // Single-line comment
            if (line.startsWith("//", i)) {
                boolean xmlDoc = csharp && line.startsWith("///", i) && !line.startsWith("////", i);
                em.put(xmlDoc ? Theme.DOC : Theme.COMMENT, line.substring(i));
                i = n;
            }
            // Block / documentation comment
            else if (line.startsWith("/*", i)) {
                boolean doc = line.startsWith("/**", i) && !line.startsWith("/**/", i);
                int end = line.indexOf("*/", i + 2);
                String color = doc ? Theme.DOC : Theme.COMMENT;
                String part = end == -1 ? line.substring(i) : line.substring(i, end + 2);
                em.put(color, doc ? Theme.docWithTags(part) : part);
                if (end == -1) {
                    next = doc ? HighlightState.C_DOC_COMMENT : HighlightState.C_BLOCK_COMMENT;
                    i = n;
                } else {
                    i = end + 2;
                }
            }
            // Preprocessor directive (C / C++ only), e.g. #include <stdio.h>
            else if (c == '#' && preprocessor && onlyBlanksBefore(line, i)) {
                int end = i + 1;
                while (end < n && Character.isWhitespace(line.charAt(end))) end++;
                while (end < n && Character.isLetterOrDigit(line.charAt(end))) end++;
                String directive = line.substring(i, end);
                em.put(Theme.META, directive);
                i = end;
                if (directive.endsWith("include") || directive.endsWith("import")) {
                    int s = i;
                    while (s < n && Character.isWhitespace(line.charAt(s))) s++;
                    int close = (s < n && line.charAt(s) == '<') ? line.indexOf('>', s) : -1;
                    if (close != -1) {
                        em.put(Theme.PLAIN, line.substring(i, s));
                        em.put(Theme.STRING, line.substring(s, close + 1));
                        i = close + 1;
                    }
                }
            }
            // C# string prefixes: @"verbatim", $"interpolated", $@"both"
            else if (csharp && (c == '@' || c == '$') && stringPrefixEnd(line, i) > 0) {
                int q = stringPrefixEnd(line, i); // index of the opening quote
                boolean verbatim = line.substring(i, q).indexOf('@') >= 0;
                em.put(Theme.STRING, line.substring(i, q));
                if (verbatim && !line.startsWith("\"\"\"", q)) {
                    int end = verbatimEnd(line, q + 1);
                    if (end == -1) {
                        em.put(Theme.STRING, line.substring(q));
                        next = HighlightState.C_VERBATIM;
                        i = n;
                    } else {
                        em.put(Theme.STRING, line.substring(q, end));
                        i = end;
                    }
                } else {
                    i = q; // ordinary / raw string body is handled by the branches below
                }
            }
            // C# verbatim identifier: @class
            else if (csharp && c == '@' && Character.isJavaIdentifierStart(peek(line, i + 1))) {
                int end = i + 1;
                while (end < n && Character.isJavaIdentifierPart(line.charAt(end))) end++;
                em.put(Theme.PLAIN, line.substring(i, end));
                i = end;
            }
            // Annotation: @Override
            else if (c == '@' && Character.isJavaIdentifierStart(peek(line, i + 1))) {
                int end = i + 1;
                while (end < n && (Character.isJavaIdentifierPart(line.charAt(end)) || line.charAt(end) == '.')) end++;
                em.put(Theme.META, line.substring(i, end));
                i = end;
            }
            // Java text block: """ ... """
            else if (c == '"' && line.startsWith("\"\"\"", i)) {
                int end = findUnescaped(line, "\"\"\"", i + 3);
                if (end == -1) {
                    em.put(Theme.STRING, Theme.stringWithEscapes(line.substring(i)));
                    next = HighlightState.C_TEXT_BLOCK;
                    i = n;
                } else {
                    em.put(Theme.STRING, Theme.stringWithEscapes(line.substring(i, end + 3)));
                    i = end + 3;
                }
            }
            // String and character literals
            else if (c == '"' || c == '\'') {
                int end = i + 1;
                boolean escaped = false;
                while (end < n) {
                    char ch = line.charAt(end++);
                    if (escaped) escaped = false;
                    else if (ch == '\\') escaped = true;
                    else if (ch == c) break;
                }
                em.put(Theme.STRING, Theme.stringWithEscapes(line.substring(i, end)));
                i = end;
            }
            // Numeric literals
            else if (Character.isDigit(c) || (c == '.' && Character.isDigit(peek(line, i + 1)))) {
                int end = scanNumber(line, i, csharp);
                em.put(Theme.NUMBER, line.substring(i, end));
                i = end;
            }
            // Identifiers and keywords
            else if (Character.isJavaIdentifierStart(c)) {
                int end = i + 1;
                while (end < n && Character.isJavaIdentifierPart(line.charAt(end))) end++;
                String word = line.substring(i, end);
                boolean definedHere = afterDefiner;
                afterDefiner = false;

                String color;
                if (definedHere && !keywords.contains(word)) {
                    color = Theme.DEFINITION;
                } else if (CONSTANTS.contains(word)) {
                    color = Theme.CONSTANT;
                } else if (SELF.contains(word)) {
                    color = Theme.VARIABLE;
                } else if (PRIMITIVES.contains(word) || (csharp && CSHARP_PRIMITIVES.contains(word))) {
                    color = Theme.TYPE;
                } else if (keywords.contains(word)) {
                    color = Theme.KEYWORD;
                    afterDefiner = DEFINERS.contains(word);
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
            }
            // Operators
            else if ("=+-*/%&|^!<>?:~".indexOf(c) != -1) {
                em.put(Theme.OPERATOR, String.valueOf(c));
                i++;
            }
            // Delimiters and whitespace
            else {
                em.put(Theme.PLAIN, String.valueOf(c));
                i++;
            }
        }

        return new HighlightResult(em.finish(), next);
    }

    private static int scanNumber(String line, int i, boolean decimalSuffix) {
        int n = line.length();
        int end = i + 1;
        char c = line.charAt(i);
        boolean hex = c == '0' && end < n && (line.charAt(end) == 'x' || line.charAt(end) == 'X');
        boolean bin = c == '0' && end < n && (line.charAt(end) == 'b' || line.charAt(end) == 'B');
        if (hex || bin) end++;
        while (end < n) {
            char ch = line.charAt(end);
            if (hex ? isHexDigit(ch) : Character.isDigit(ch)) {
                end++;
            } else if (!hex && ch == '.' && Character.isDigit(peek(line, end + 1))) {
                end++;
            } else if (!hex && !bin && (ch == 'e' || ch == 'E')
                    && (Character.isDigit(peek(line, end + 1))
                    || ((peek(line, end + 1) == '+' || peek(line, end + 1) == '-') && Character.isDigit(peek(line, end + 2))))) {
                end += 2; // exponent such as 1e9 or 2.5e-3
            } else if (ch == 'f' || ch == 'F' || ch == 'l' || ch == 'L' || ch == 'd' || ch == 'D'
                    || ch == 'u' || ch == 'U' || ch == '_'
                    || (decimalSuffix && (ch == 'm' || ch == 'M'))) {
                end++;
            } else {
                break;
            }
        }
        return end;
    }

    /**
     * If a C# string prefix ({@code @}, {@code $}, {@code $@}, {@code @$}) starts at {@code i} and is
     * followed by a double quote, returns the index of that quote; otherwise -1.
     */
    private static int stringPrefixEnd(String line, int i) {
        int p = i;
        while (p < line.length() && p - i < 2 && (line.charAt(p) == '@' || line.charAt(p) == '$')) p++;
        return p < line.length() && line.charAt(p) == '"' ? p : -1;
    }

    /** Index just after the closing quote of a verbatim string body starting at {@code from}, or -1. */
    private static int verbatimEnd(String line, int from) {
        int i = from;
        while (i < line.length()) {
            if (line.charAt(i) == '"') {
                if (peek(line, i + 1) == '"') { i += 2; continue; } // "" is an escaped quote
                return i + 1;
            }
            i++;
        }
        return -1;
    }

    private static int findUnescaped(String line, String needle, int from) {
        int i = from;
        while (i < line.length()) {
            if (line.charAt(i) == '\\') { i += 2; continue; }
            if (line.startsWith(needle, i)) return i;
            i++;
        }
        return -1;
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

    private static char peek(String line, int index) {
        return index < line.length() ? line.charAt(index) : '\0';
    }

    private static boolean isHexDigit(char c) {
        return Character.isDigit(c) || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }
}
