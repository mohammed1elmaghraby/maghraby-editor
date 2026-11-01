package org.maghraby.syntax;

import java.util.Set;

/**
 * Syntax highlighter for Bash / POSIX shell / Zsh scripts.
 *
 * Understands: shebang, comments, single and double quoted strings (also across lines), $VAR, ${...},
 * $(...), $((...)), backticks, assignments, commands vs. arguments, options (-f, --force), keywords,
 * builtins, function definitions, test brackets, redirections, pipes, here-documents and backslash
 * line continuations.
 *
 * Known limits (a line-based highlighter cannot track more without a real parser):
 *  - A here-document ends at the first line that is only an upper-case word (EOF, END, EOT...) or
 *    eof/end/eot, not at the exact delimiter that opened it.
 *  - Nested quoting inside $(...) is coloured as ordinary tokens, not matched pairs.
 *  - Case patterns such as "start)" look like command names.
 *
 * Stateless and thread-safe: all parsing state lives in the per-call {@link Scan}.
 */
public class BashHighlighter implements SyntaxHighlighter {

    private static final Set<String> EXTENSIONS = Set.of(
            "sh", "bash", "zsh", "ksh",
            // dotfiles: ".bashrc" has the "extension" bashrc
            "bashrc", "bash_profile", "bash_aliases", "bash_logout", "profile", "zshrc", "zprofile", "zshenv");

    private static final Set<String> KEYWORDS = Set.of(
            "if", "then", "else", "elif", "fi", "for", "while", "until", "do", "done",
            "case", "esac", "in", "function", "select", "time", "coproc");

    /** Keywords after which a command name is expected. */
    private static final Set<String> COMMAND_AFTER = Set.of(
            "if", "then", "else", "elif", "while", "until", "do", "time", "coproc");

    private static final Set<String> BUILTINS = Set.of(
            ".", "alias", "bg", "bind", "break", "builtin", "caller", "cd", "command", "compgen", "complete",
            "continue", "declare", "dirs", "disown", "echo", "enable", "eval", "exec", "exit", "export",
            "false", "fc", "fg", "getopts", "hash", "help", "history", "jobs", "kill", "let", "local",
            "logout", "mapfile", "popd", "printf", "pushd", "pwd", "read", "readarray", "readonly", "return",
            "set", "shift", "shopt", "source", "suspend", "test", "times", "trap", "true", "type", "typeset",
            "ulimit", "umask", "unalias", "unset", "wait");

    /** Commands that run another command: the word after them is a command name too. */
    private static final Set<String> WRAPPERS = Set.of(
            "sudo", "doas", "nohup", "env", "exec", "xargs", "command", "builtin", "nice", "watch", "time");

    @Override
    public boolean supportsExtension(String extension) {
        return EXTENSIONS.contains(extension.toLowerCase());
    }

    @Override
    public HighlightResult highlightLine(String line, HighlightState state) {
        if (state == HighlightState.BASH_HEREDOC) {
            Emitter em = new Emitter(line.length());
            if (isHeredocEnd(line)) {
                em.put(Theme.META, line);
                return new HighlightResult(em.finish(), HighlightState.NORMAL);
            }
            heredocBody(em, line);
            return new HighlightResult(em.finish(), HighlightState.BASH_HEREDOC);
        }
        return new Scan(line, state).run();
    }

    /** Heuristic end of a here-document body: a line holding only an upper-case word, or eof/end/eot. */
    static boolean isHeredocEnd(String line) {
        String t = line.strip();
        if (t.isEmpty()) return false;
        if (t.equals("eof") || t.equals("end") || t.equals("eot")) return true;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z') || c == '_' || (i > 0 && c >= '0' && c <= '9');
            if (!ok) return false;
        }
        return true;
    }

    // ------------------------------------------------------------------

    /**
     * End of the variable expansion starting at the '$' at {@code i} ("$name", "${...}", "$1", "$?",
     * or just "$(" / "$((" which only covers the opener). Returns i + 1 when '$' is not an expansion.
     */
    static int variableEnd(String line, int i) {
        int n = line.length();
        if (i + 1 >= n) return i + 1;
        char d = line.charAt(i + 1);
        if (d == '{') {
            int e = line.indexOf('}', i + 2);
            return e < 0 ? n : e + 1;
        }
        if (d == '(') return line.startsWith("$((", i) ? i + 3 : i + 2;
        if (identStart(d)) {
            int e = i + 2;
            while (e < n && ident(line.charAt(e))) e++;
            return e;
        }
        if ((d >= '0' && d <= '9') || "@*#?$!-_".indexOf(d) >= 0) return i + 2;
        return i + 1;
    }

    /** Here-document body: string colour with $expansions highlighted. */
    private static void heredocBody(Emitter em, String line) {
        int n = line.length();
        int run = 0;
        int j = 0;
        while (j < n) {
            char c = line.charAt(j);
            if (c == '\\' && j + 1 < n) {
                j += 2;
            } else if (c == '$') {
                int e = variableEnd(line, j);
                if (e > j + 1) {
                    em.put(Theme.STRING, line.substring(run, j));
                    em.put(Theme.VARIABLE, line.substring(j, e));
                    j = e;
                    run = j;
                } else {
                    j++;
                }
            } else {
                j++;
            }
        }
        em.put(Theme.STRING, line.substring(Math.min(run, n)));
    }

    private static boolean blank(char c) { return c == ' ' || c == '\t'; }

    private static boolean identStart(char c) { return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_'; }

    private static boolean ident(char c) { return identStart(c) || (c >= '0' && c <= '9'); }

    private static boolean allDigits(String s) {
        if (s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) < '0' || s.charAt(i) > '9') return false;
        return true;
    }

    /** One pass over one line. */
    private static final class Scan {
        private final String line;
        private final int n;
        private final HighlightState startState;
        private final Emitter em;

        private boolean cmdPos;          // the next word is a command name
        private boolean plainNext;       // the next word is a redirection target
        private boolean expectVar;       // "for x" / "select x"
        private boolean expectIn;        // "for x in" / "case $x in"
        private boolean expectFuncName;  // "function name"
        private boolean pendingHeredoc;
        private boolean continued;       // line ends with a backslash
        private boolean inBacktick;
        private HighlightState open;     // multi-line string left open at end of line, or null

        Scan(String line, HighlightState startState) {
            this.line = line;
            this.n = line.length();
            this.startState = startState;
            this.em = new Emitter(n);
        }

        HighlightResult run() {
            int i = 0;
            cmdPos = startState == HighlightState.NORMAL || startState == HighlightState.BASH_CONT_CMD;
            if (startState == HighlightState.BASH_DQ) {
                i = scanDouble(0, true);
                cmdPos = false;
            } else if (startState == HighlightState.BASH_SQ) {
                i = scanSingle(0, true);
                cmdPos = false;
            }

            while (i < n) {
                char c = line.charAt(i);

                if (blank(c)) {
                    int j = i;
                    while (j < n && blank(line.charAt(j))) j++;
                    em.put(Theme.PLAIN, line.substring(i, j));
                    i = j;
                } else if (c == '#' && (i == 0 || commentBoundary(line.charAt(i - 1)))) {
                    em.put(i == 0 && line.startsWith("#!") ? Theme.META : Theme.COMMENT, line.substring(i));
                    i = n;
                } else if (c == '\'') {
                    i = scanSingle(i, false);
                    cmdPos = false;
                } else if (c == '"') {
                    i = scanDouble(i, false);
                    cmdPos = false;
                } else if (c == '`') {
                    inBacktick = !inBacktick;
                    em.put(Theme.ESCAPE, "`");
                    cmdPos = inBacktick;
                    i++;
                } else if (c == '$') {
                    i = scanDollar(i);
                } else if (c == '\\') {
                    if (i + 1 >= n) {
                        em.put(Theme.ESCAPE, "\\");
                        continued = true;
                        i = n;
                    } else {
                        em.put(Theme.ESCAPE, line.substring(i, i + 2));
                        i += 2;
                    }
                } else if (isOperatorChar(c)) {
                    i = scanOperator(i);
                } else {
                    int j = wordEnd(i);
                    if (j == i) j = i + 1; // never stall
                    i = handleWord(i, j);
                }
            }

            HighlightState next = HighlightState.NORMAL;
            if (open != null) next = open;
            else if (pendingHeredoc) next = HighlightState.BASH_HEREDOC;
            else if (continued) next = cmdPos ? HighlightState.BASH_CONT_CMD : HighlightState.BASH_CONT_ARG;
            return new HighlightResult(em.finish(), next);
        }

        private static boolean commentBoundary(char prev) {
            return blank(prev) || prev == ';' || prev == '&' || prev == '|' || prev == '(';
        }

        private static boolean isOperatorChar(char c) {
            return c == ';' || c == '&' || c == '|' || c == '<' || c == '>' || c == '(' || c == ')';
        }

        /** End (exclusive) of a bare word starting at {@code i}. */
        private int wordEnd(int i) {
            int k = i;
            while (k < n) {
                char ch = line.charAt(k);
                if (blank(ch) || ";&|<>()'\"$`".indexOf(ch) >= 0) break;
                if (ch == '\\') {
                    k = Math.min(n, k + 2);
                } else {
                    k++;
                }
            }
            return k;
        }

        // ---------------- strings ----------------

        private int scanSingle(int i, boolean continuing) {
            int from = continuing ? i : i + 1;
            int e = line.indexOf('\'', from);
            if (e < 0) {
                em.put(Theme.STRING, line.substring(i));
                open = HighlightState.BASH_SQ;
                return n;
            }
            em.put(Theme.STRING, line.substring(i, e + 1));
            return e + 1;
        }

        private int scanDouble(int i, boolean continuing) {
            int j = i;
            if (!continuing) {
                em.put(Theme.STRING, "\"");
                j = i + 1;
            }
            int run = j; // start of the pending plain-string run
            while (j < n) {
                char c = line.charAt(j);
                if (c == '"') {
                    em.put(Theme.STRING, line.substring(run, j));
                    em.put(Theme.STRING, "\"");
                    return j + 1;
                }
                if (c == '\\' && j + 1 < n) {
                    em.put(Theme.STRING, line.substring(run, j));
                    em.put(Theme.ESCAPE, line.substring(j, j + 2));
                    j += 2;
                    run = j;
                    continue;
                }
                if (c == '$' && j + 1 < n) {
                    int e = variableEnd(line, j);
                    if (e > j + 1) {
                        em.put(Theme.STRING, line.substring(run, j));
                        em.put(Theme.VARIABLE, line.substring(j, e));
                        j = e;
                        run = j;
                        continue;
                    }
                }
                j++;
            }
            em.put(Theme.STRING, line.substring(run, n));
            open = HighlightState.BASH_DQ;
            return n;
        }

        private int scanDollar(int i) {
            if (i + 1 < n && line.charAt(i + 1) == '\'') { // $'...' ANSI-C string
                int e = i + 2;
                while (e < n) {
                    char ch = line.charAt(e);
                    if (ch == '\\') e += 2;
                    else if (ch == '\'') { e++; break; }
                    else e++;
                }
                e = Math.min(e, n);
                em.put(Theme.STRING, Theme.stringWithEscapes(line.substring(i, e)));
                cmdPos = false;
                return e;
            }
            int e = variableEnd(line, i);
            if (e == i + 1) {
                em.put(Theme.PLAIN, "$");
                cmdPos = false;
                return e;
            }
            em.put(Theme.VARIABLE, line.substring(i, e));
            // "$(" starts a command; "$((" starts arithmetic; "$name" / "${...}" are plain operands.
            cmdPos = line.charAt(i + 1) == '(' && e == i + 2;
            return e;
        }

        // ---------------- operators ----------------

        private int scanOperator(int i) {
            // here-document: <<WORD, <<-WORD, <<'WORD', <<"WORD" (but not <<< here-string)
            if (line.startsWith("<<", i) && !line.startsWith("<<<", i)) {
                int j = i + 2;
                if (j < n && line.charAt(j) == '-') j++;
                int k = j;
                while (k < n && blank(line.charAt(k))) k++;
                if (k < n) {
                    char q = line.charAt(k);
                    boolean quoted = q == '\'' || q == '"' || q == '\\';
                    int w = quoted ? k + 1 : k;
                    int we = w;
                    while (we < n && ident(line.charAt(we))) we++;
                    if (we > w && identStart(line.charAt(w))) {
                        int end = we;
                        if (quoted && q != '\\' && end < n && line.charAt(end) == q) end++;
                        em.put(Theme.OPERATOR, line.substring(i, j));
                        em.put(Theme.PLAIN, line.substring(j, k));
                        em.put(Theme.META, line.substring(k, end));
                        pendingHeredoc = true;
                        cmdPos = false;
                        return end;
                    }
                }
            }

            String op = matchOperator(i);
            em.put(Theme.OPERATOR, op);
            switch (op) {
                case "&&": case "||": case "|": case "|&": case "&": case "<(": case ">(": case "(":
                    cmdPos = true;
                    break;
                case ";": case ";;": case ";&":
                    cmdPos = true;
                    expectIn = false;
                    expectVar = false;
                    break;
                case "((":
                    cmdPos = false;
                    expectIn = false;
                    expectVar = false;
                    break;
                case ")":
                    cmdPos = false;
                    break;
                default: // redirections: the following word is a file name / descriptor
                    plainNext = true;
                    break;
            }
            return i + op.length();
        }

        private String matchOperator(int i) {
            String[] multi = {"<<<", "&&", "||", ";;", ";&", "|&", "((", "<(", ">(", ">>", ">&", "&>", "<&", "<>", "<<"};
            for (String m : multi) if (line.startsWith(m, i)) return m;
            return line.substring(i, i + 1);
        }

        // ---------------- words ----------------

        /** Colours the bare word line[start, end) and returns the index after it. */
        private int handleWord(int start, int end) {
            String w = line.substring(start, end);

            // "2>file", "1>&2": the digits are part of the redirection
            if (allDigits(w) && end < n && (line.charAt(end) == '>' || line.charAt(end) == '<')) {
                em.put(Theme.OPERATOR, w);
                return end;
            }
            if (plainNext) {
                plainNext = false;
                em.put(allDigits(w) ? Theme.NUMBER : Theme.PLAIN, w);
                return end;
            }
            if (expectFuncName) {
                expectFuncName = false;
                em.put(Theme.DEFINITION, w);
                cmdPos = false;
                return end;
            }
            if (expectVar) {
                expectVar = false;
                em.put(Theme.VARIABLE, w);
                cmdPos = false;
                return end;
            }
            if (expectIn && w.equals("in")) {
                expectIn = false;
                em.put(Theme.KEYWORD, w);
                cmdPos = false;
                return end;
            }

            int eq = assignmentEquals(w);
            if (eq > 0) {
                int nameEnd = w.charAt(eq - 1) == '+' ? eq - 1 : eq;
                em.put(Theme.VARIABLE, w.substring(0, nameEnd));
                em.put(Theme.OPERATOR, w.substring(nameEnd, eq + 1));
                String value = w.substring(eq + 1);
                em.put(allDigits(value) ? Theme.NUMBER : Theme.PLAIN, value);
                return end; // a following command word (FOO=1 cmd) keeps cmdPos
            }

            if (cmdPos) {
                if (w.startsWith("-") && w.length() > 1) { // option of a wrapper: sudo -u user ...
                    em.put(Theme.OPTION, w);
                    return end;
                }
                // name() { ... } function definition
                int p = end;
                while (p < n && blank(line.charAt(p))) p++;
                if (line.startsWith("()", p) && !KEYWORDS.contains(w)) {
                    em.put(Theme.DEFINITION, w);
                    em.put(Theme.PLAIN, line.substring(end, p));
                    em.put(Theme.OPERATOR, "()");
                    return p + 2;
                }
                if (KEYWORDS.contains(w)) {
                    em.put(Theme.KEYWORD, w);
                    switch (w) {
                        case "for": case "select": expectVar = true; expectIn = true; cmdPos = false; break;
                        case "case": expectIn = true; cmdPos = false; break;
                        case "function": expectFuncName = true; cmdPos = false; break;
                        default: cmdPos = COMMAND_AFTER.contains(w); break;
                    }
                    return end;
                }
                switch (w) {
                    case "{": em.put(Theme.KEYWORD, w); return end;                       // cmdPos stays true
                    case "}": em.put(Theme.KEYWORD, w); cmdPos = false; return end;
                    case "[": case "[[": em.put(Theme.KEYWORD, w); cmdPos = false; return end;
                    case "!": em.put(Theme.OPERATOR, w); return end;                       // cmdPos stays true
                    default: break;
                }
                em.put(BUILTINS.contains(w) ? Theme.BUILTIN : Theme.FUNCTION, w);
                cmdPos = WRAPPERS.contains(w);
                return end;
            }

            // argument position
            switch (w) {
                case "]": case "]]": em.put(Theme.KEYWORD, w); return end;
                case "==": case "!=": case "=~": case "=": em.put(Theme.OPERATOR, w); return end;
                default: break;
            }
            if (w.length() > 1 && w.charAt(0) == '-') {
                int e = w.indexOf('=');
                if (e > 1 && w.charAt(1) == '-') {            // --name=value
                    em.put(Theme.OPTION, w.substring(0, e));
                    em.put(Theme.OPERATOR, "=");
                    em.put(Theme.PLAIN, w.substring(e + 1));
                } else {
                    em.put(Theme.OPTION, w);
                }
            } else if (allDigits(w)) {
                em.put(Theme.NUMBER, w);
            } else {
                em.put(Theme.PLAIN, w);
            }
            return end;
        }

        /** Index of '=' when {@code w} looks like NAME=, NAME+=, or NAME[idx]=, otherwise -1. */
        private static int assignmentEquals(String w) {
            int len = w.length();
            if (len == 0 || !identStart(w.charAt(0))) return -1;
            int k = 1;
            while (k < len && ident(w.charAt(k))) k++;
            if (k < len && w.charAt(k) == '[') {
                int close = w.indexOf(']', k);
                if (close < 0) return -1;
                k = close + 1;
            }
            if (k < len && w.charAt(k) == '+') k++;
            return k < len && w.charAt(k) == '=' ? k : -1;
        }
    }
}
