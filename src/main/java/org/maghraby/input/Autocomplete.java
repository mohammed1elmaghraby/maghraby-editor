package org.maghraby.input;

import org.maghraby.runner.CodeRunner;
import org.maghraby.lsp.LspClient;
import org.maghraby.lsp.LspDocumentSync;

import java.util.ArrayList;
import java.util.List;

import static org.maghraby.core.State.*;

/**
 * Completion popup logic: asks the language server, or falls back to words from the file and keywords.
 */
public final class Autocomplete {
    private Autocomplete() {}

    /** Called from the LSP thread once completions for the current cursor position arrive. */
    static void showSuggestionsUi(List<LspDocumentSync.Completion> suggestions) {
        if (suggestions == null || suggestions.isEmpty()) {
            closeSuggestions(false);
            return;
        }
        activeSuggestions = suggestions;
        suggestionIndex = 0;
        showingSuggestions = true;
        needsRedraw = true;
    }

    public static void closeSuggestions() {
        closeSuggestions(true);
    }

    /** @param cancelPending also drop a request still in flight, so it cannot pop up later. */
    public static void closeSuggestions(boolean cancelPending) {
        if (cancelPending) {
            LspDocumentSync s = D().lsp;
            if (s != null) s.cancelSuggestions();
        }
        showingSuggestions = false;
        activeSuggestions = null;
        needsRedraw = true;
    }

    /** The identifier characters immediately before the cursor ("pri" in "System.out.pri|"). */
    public static String identifierPrefix() {
        String line = ed().getLine(cursorRow);
        int end = Math.min(cursorCol, line.length());
        int start = end;
        while (start > 0 && Character.isJavaIdentifierPart(line.charAt(start - 1))) start--;
        return line.substring(start, end);
    }

    /** True when the character before the cursor is a member-access trigger: '.', '->' or '::'. */
    public static boolean afterTriggerChar() {
        String line = ed().getLine(cursorRow);
        int end = Math.min(cursorCol, line.length());
        if (end == 0) return false;
        char c = line.charAt(end - 1);
        if (c == '.') return true;
        if (end >= 2) {
            char p = line.charAt(end - 2);
            return (c == '>' && p == '-') || (c == ':' && p == ':');
        }
        return false;
    }

    /** Asks the language server for completions, but only when it makes sense at the cursor. */
    public static void triggerCompletion() {
        LspDocumentSync s = D().lsp;
        String prefix = identifierPrefix();
        boolean useServer = lspUsable(s);
        if (prefix.isEmpty() && !afterTriggerChar()) {
            if (showingSuggestions) closeSuggestions(); else if (s != null) s.cancelSuggestions();
            return;
        }
        if (useServer) {
            s.requestSuggestions(cursorRow, cursorCol, prefix, Autocomplete::showSuggestionsUi);
        } else if (!prefix.isEmpty()) {
            // No language server (not installed, still starting, or crashed): complete from the words
            // already in the file plus the language's keywords.
            showSuggestionsUi(localSuggestions(prefix));
        } else if (showingSuggestions) {
            closeSuggestions();
        }
    }

    /** True when the language server is running and has finished its handshake. */
    public static boolean lspUsable(LspDocumentSync s) {
        if (s == null) return false;
        LspClient c = s.client();
        return c.isAlive() && c.ready().isDone() && !c.ready().isCompletedExceptionally();
    }

    public static final String[] JAVA_WORDS = {
            "abstract", "boolean", "break", "byte", "case", "catch", "char", "class", "continue", "default",
            "do", "double", "else", "enum", "extends", "final", "finally", "float", "for", "if", "implements",
            "import", "instanceof", "int", "interface", "long", "new", "null", "package", "private",
            "protected", "public", "return", "short", "static", "super", "switch", "this", "throw", "throws",
            "try", "void", "volatile", "while", "String", "System", "List", "Map", "ArrayList", "HashMap"};

    public static final String[] C_WORDS = {
            "auto", "break", "case", "char", "const", "continue", "default", "do", "double", "else", "enum",
            "extern", "float", "for", "goto", "if", "include", "int", "long", "namespace", "return", "short",
            "signed", "sizeof", "static", "struct", "switch", "typedef", "union", "unsigned", "void",
            "volatile", "while", "printf", "scanf", "malloc", "free", "std", "string", "vector", "cout", "cin"};

    public static final String[] PY_WORDS = {
            "and", "as", "assert", "break", "class", "continue", "def", "del", "elif", "else", "except",
            "finally", "for", "from", "global", "if", "import", "in", "is", "lambda", "None", "not", "or",
            "pass", "raise", "return", "self", "True", "False", "try", "while", "with", "yield", "print",
            "range", "len", "str", "int", "list", "dict"};

    public static String[] keywordsFor(String filename) {
        CodeRunner.Language l = CodeRunner.detect(filename);
        if (l == null) return new String[0];
        switch (l) {
            case JAVA: return JAVA_WORDS;
            case C: case CPP: return C_WORDS;
            case PYTHON: return PY_WORDS;
            default: return new String[0];
        }
    }

    /** Word-based completion: identifiers near the cursor and language keywords that start with {@code prefix}. */
    public static List<LspDocumentSync.Completion> localSuggestions(String prefix) {
        String lower = prefix.toLowerCase(java.util.Locale.ROOT);
        java.util.Map<String, Integer> counts = new java.util.HashMap<>();
        java.util.Map<String, Boolean> isKeyword = new java.util.HashMap<>();

        // Scan a window around the cursor so a huge file doesn't make every keystroke slow.
        int from = Math.max(0, cursorRow - 150);
        int to = Math.min(ed().getLineCount(), cursorRow + 150);
        for (String line : ed().getLines(from, to)) {
            int n = line.length();
            int i = 0;
            while (i < n) {
                if (Character.isJavaIdentifierStart(line.charAt(i))) {
                    int j = i + 1;
                    while (j < n && Character.isJavaIdentifierPart(line.charAt(j))) j++;
                    if (j - i > prefix.length() && line.regionMatches(true, i, prefix, 0, prefix.length())) {
                        counts.merge(line.substring(i, j), 1, Integer::sum);
                    }
                    i = j;
                } else {
                    i++;
                }
            }
        }
        for (String k : keywordsFor(D().filename)) {
            if (k.length() > prefix.length() && k.toLowerCase(java.util.Locale.ROOT).startsWith(lower)) {
                counts.merge(k, 1, Integer::sum);
                isKeyword.put(k, Boolean.TRUE);
            }
        }

        List<String> words = new ArrayList<>(counts.keySet());
        words.sort((a, b) -> {
            boolean ca = a.startsWith(prefix), cb = b.startsWith(prefix); // same case as typed first
            if (ca != cb) return ca ? -1 : 1;
            int d = counts.get(b) - counts.get(a);                         // then the most frequent
            return d != 0 ? d : a.compareTo(b);
        });

        List<LspDocumentSync.Completion> out = new ArrayList<>();
        int replaceFrom = cursorCol - prefix.length();
        for (String w : words) {
            out.add(new LspDocumentSync.Completion(w, isKeyword.containsKey(w) ? "keyword" : "word",
                    w, isKeyword.containsKey(w) ? 14 : 0, Math.max(0, replaceFrom)));
            if (out.size() >= 30) break;
        }
        return out;
    }

    /** Replaces the typed prefix with the highlighted suggestion. */
    public static void acceptSuggestion() {
        List<LspDocumentSync.Completion> list = activeSuggestions;
        if (list == null || list.isEmpty()) return;
        LspDocumentSync.Completion c = list.get(Math.max(0, Math.min(suggestionIndex, list.size() - 1)));
        closeSuggestions();

        int from = Math.min(c.replaceFromCol, cursorCol);
        for (int n = cursorCol - from; n > 0; n--) {
            int[] pos = ed().deleteAt(cursorRow, cursorCol);
            cursorRow = pos[0];
            cursorCol = pos[1];
        }
        int[] pos = ed().insertAt(cursorRow, cursorCol, c.insertText);
        cursorRow = pos[0];
        cursorCol = pos[1];
    }

    static boolean suggestionsOpen() {
        List<LspDocumentSync.Completion> l = activeSuggestions;
        return showingSuggestions && l != null && !l.isEmpty();
    }
}
