package org.maghraby.syntax;

import java.util.Locale;
import java.util.Set;

/**
 * Syntax highlighter for SQL (ANSI SQL plus the common PostgreSQL, MySQL, SQLite and T-SQL dialect words)
 * using ANSI escape codes.
 *
 * Keywords and type names are matched case-insensitively. Handles {@code -- line} comments, MySQL
 * {@code # line} comments, slash-star block comments across lines, single-quoted strings
 * (with {@code ''} escapes, across lines), quoted identifiers ("x", `x`, [x]), numbers, {@code @variables},
 * {@code :named} / {@code $1} parameters, function calls and operators.
 */
public class SqlHighlighter implements SyntaxHighlighter {

    private static final Set<String> KEYWORDS = Set.of(
            "add", "all", "alter", "and", "any", "as", "asc", "authorization", "backup", "begin", "between",
            "break", "by", "cascade", "case", "check", "checkpoint", "close", "cluster", "collate", "column",
            "commit", "constraint", "continue", "create", "cross", "current", "cursor", "database", "deallocate",
            "declare", "default", "delete", "desc", "distinct", "do", "drop", "else", "elsif", "end", "escape",
            "except", "exec", "execute", "exists", "explain", "fetch", "filter", "for", "foreign", "from",
            "full", "function", "grant", "group", "having", "if", "ilike", "in", "index", "inner", "insert",
            "intersect", "into", "is", "join", "key", "left", "like", "limit", "lock", "loop", "materialized",
            "merge", "natural", "not", "nulls", "of", "offset", "on", "only", "open", "or", "order", "outer",
            "over", "partition", "pragma", "primary", "procedure", "range", "recursive", "references",
            "replace", "restrict", "return", "returning", "returns", "revoke", "right", "rollback", "row",
            "rows", "savepoint", "schema", "select", "sequence", "set", "show", "some", "table", "temp",
            "temporary", "then", "to", "top", "transaction", "trigger", "truncate", "union", "unique",
            "unlock", "update", "use", "using", "values", "vacuum", "view", "when", "where", "while", "window",
            "with", "work", "autoincrement", "auto_increment", "identity", "unsigned", "zerofill");

    private static final Set<String> TYPES = Set.of(
            "bigint", "bigserial", "binary", "bit", "blob", "bool", "boolean", "bytea", "char", "character",
            "clob", "date", "datetime", "datetime2", "decimal", "double", "enum", "float", "int", "int2",
            "int4", "int8", "integer", "interval", "json", "jsonb", "longtext", "mediumint", "mediumtext",
            "money", "nchar", "number", "numeric", "nvarchar", "nvarchar2", "precision", "real", "serial",
            "smallint", "smallserial", "text", "time", "timestamp", "timestamptz", "tinyint", "uuid",
            "varbinary", "varchar", "varchar2", "xml");

    private static final Set<String> CONSTANTS = Set.of("true", "false", "null", "unknown");

    /** Words that are followed by the name of the object being created / altered / dropped. */
    private static final Set<String> DEFINERS = Set.of(
            "table", "view", "index", "database", "schema", "function", "procedure", "trigger", "sequence");

    @Override
    public boolean supportsExtension(String extension) {
        return "sql".equalsIgnoreCase(extension);
    }

    @Override
    public HighlightResult highlightLine(String line, HighlightState state) {
        Emitter em = new Emitter(line.length());
        int n = line.length();
        int i = 0;
        HighlightState next = HighlightState.NORMAL;
        boolean afterDefiner = false;

        // ---- continuation of a multi-line construct ----
        if (state == HighlightState.C_BLOCK_COMMENT) {
            int end = line.indexOf("*/");
            if (end == -1) {
                em.put(Theme.COMMENT, line);
                return new HighlightResult(em.finish(), state);
            }
            em.put(Theme.COMMENT, line.substring(0, end + 2));
            i = end + 2;
        } else if (state == HighlightState.SQL_STRING) {
            int end = stringEnd(line, 0);
            if (end == -1) {
                em.put(Theme.STRING, line);
                return new HighlightResult(em.finish(), state);
            }
            em.put(Theme.STRING, line.substring(0, end));
            i = end;
        }

        while (i < n) {
            char c = line.charAt(i);

            // "# ..." is a MySQL comment; "#temp" (T-SQL) and "#>" (PostgreSQL) are not
            if (line.startsWith("--", i)
                    || (c == '#' && (i + 1 >= n || Character.isWhitespace(line.charAt(i + 1)) || line.charAt(i + 1) == '#'))) {
                em.put(Theme.COMMENT, line.substring(i));
                i = n;
            } else if (line.startsWith("/*", i)) {
                int end = line.indexOf("*/", i + 2);
                if (end == -1) {
                    em.put(Theme.COMMENT, line.substring(i));
                    next = HighlightState.C_BLOCK_COMMENT;
                    i = n;
                } else {
                    em.put(Theme.COMMENT, line.substring(i, end + 2));
                    i = end + 2;
                }
            } else if (c == '\'') {
                int end = stringEnd(line, i + 1);
                if (end == -1) {
                    em.put(Theme.STRING, line.substring(i));
                    next = HighlightState.SQL_STRING;
                    i = n;
                } else {
                    em.put(Theme.STRING, line.substring(i, end));
                    i = end;
                }
            } else if (c == '"' || c == '`' || c == '[') {
                // Quoted identifier
                char close = c == '[' ? ']' : c;
                int end = line.indexOf(close, i + 1);
                if (c == '[' && end == -1) {
                    em.put(Theme.PLAIN, String.valueOf(c));
                    i++;
                } else {
                    end = end == -1 ? n : end + 1;
                    em.put(afterDefiner ? Theme.DEFINITION : Theme.PLAIN, line.substring(i, end));
                    afterDefiner = false;
                    i = end;
                }
            } else if (Character.isDigit(c) || (c == '.' && Character.isDigit(peek(line, i + 1)))) {
                int end = i + 1;
                boolean hex = c == '0' && (peek(line, end) == 'x' || peek(line, end) == 'X');
                if (hex) end++;
                while (end < n) {
                    char ch = line.charAt(end);
                    if (hex ? isHex(ch) : (Character.isDigit(ch) || ch == '_')) end++;
                    else if (!hex && ch == '.' && Character.isDigit(peek(line, end + 1))) end++;
                    else if (!hex && (ch == 'e' || ch == 'E') && (Character.isDigit(peek(line, end + 1))
                            || ((peek(line, end + 1) == '+' || peek(line, end + 1) == '-')
                            && Character.isDigit(peek(line, end + 2))))) end += 2;
                    else break;
                }
                em.put(Theme.NUMBER, line.substring(i, end));
                i = end;
            } else if (c == '@' || c == ':' && Character.isLetter(peek(line, i + 1)) && peek(line, i - 1) != ':'
                    || c == '$' && Character.isDigit(peek(line, i + 1))) {
                // @variable, :named_param, $1 positional parameter
                int end = i + 1;
                if (c == '@' && peek(line, end) == '@') end++;
                while (end < n && (Character.isLetterOrDigit(line.charAt(end)) || line.charAt(end) == '_')) end++;
                em.put(end > i + 1 ? Theme.VARIABLE : Theme.OPERATOR, line.substring(i, end));
                i = end;
            } else if (Character.isLetter(c) || c == '_') {
                int end = i + 1;
                while (end < n && (Character.isLetterOrDigit(line.charAt(end)) || line.charAt(end) == '_')) end++;
                String word = line.substring(i, end);
                String lower = word.toLowerCase(Locale.ROOT);
                boolean dotted = peek(line, i - 1) == '.' || peek(line, end) == '.';

                String color;
                if (afterDefiner && !KEYWORDS.contains(lower) && !dotted) {
                    color = Theme.DEFINITION;
                    afterDefiner = false;
                } else if (!dotted && CONSTANTS.contains(lower)) {
                    color = Theme.CONSTANT;
                } else if (!dotted && TYPES.contains(lower) && nextNonBlank(line, end) != '.') {
                    color = Theme.TYPE;
                } else if (!dotted && KEYWORDS.contains(lower)) {
                    color = Theme.KEYWORD;
                    // "IF NOT EXISTS" and friends keep the pending name; plain DDL nouns arm it
                    afterDefiner = DEFINERS.contains(lower)
                            || (afterDefiner && (lower.equals("if") || lower.equals("not") || lower.equals("exists")));
                } else if (nextNonBlank(line, end) == '(') {
                    color = Theme.BUILTIN;
                } else {
                    color = Theme.PLAIN;
                    afterDefiner = false;
                }
                em.put(color, word);
                i = end;
            } else if ("=+-*/%&|^!<>~".indexOf(c) != -1) {
                em.put(Theme.OPERATOR, String.valueOf(c));
                i++;
            } else {
                em.put(Theme.PLAIN, String.valueOf(c));
                i++;
            }
        }
        return new HighlightResult(em.finish(), next);
    }

    /** Index just after the closing quote of a single-quoted string body starting at {@code from}, or -1. */
    private static int stringEnd(String line, int from) {
        int i = from;
        while (i < line.length()) {
            char ch = line.charAt(i);
            if (ch == '\\' && i + 1 < line.length()) { i += 2; continue; } // MySQL-style escape
            if (ch == '\'') {
                if (peek(line, i + 1) == '\'') { i += 2; continue; }       // '' is an escaped quote
                return i + 1;
            }
            i++;
        }
        return -1;
    }

    private static char nextNonBlank(String line, int from) {
        int p = from;
        while (p < line.length() && Character.isWhitespace(line.charAt(p))) p++;
        return p < line.length() ? line.charAt(p) : '\0';
    }

    private static char peek(String line, int index) {
        return index >= 0 && index < line.length() ? line.charAt(index) : '\0';
    }

    private static boolean isHex(char c) {
        return Character.isDigit(c) || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }
}
