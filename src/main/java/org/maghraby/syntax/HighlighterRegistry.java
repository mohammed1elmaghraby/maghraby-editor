package org.maghraby.syntax;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Registry storing all registered highlighters and resolving the correct
 * implementation based on a file's extension.
 */
public final class HighlighterRegistry {

    private final List<SyntaxHighlighter> highlighters = new ArrayList<>();
    private final SyntaxHighlighter fallback = new PlainTextHighlighter();

    public HighlighterRegistry() {
        registerDefaultHighlighters();
    }

    private void registerDefaultHighlighters() {
        // Java
        register(new CLikeHighlighter(
                Set.of("java"),
                Set.of(
                        "abstract", "assert", "boolean", "break", "byte", "case", "catch",
                        "char", "class", "const", "continue", "default", "do", "double",
                        "else", "enum", "extends", "final", "finally", "float", "for",
                        "if", "implements", "import", "instanceof", "int", "interface",
                        "long", "native", "new", "package", "private", "protected",
                        "public", "return", "short", "static", "strictfp", "super",
                        "switch", "synchronized", "this", "throw", "throws", "transient",
                        "try", "void", "volatile", "while", "var", "record", "yield",
                        "sealed", "permits", "true", "false", "null"
                )
        ));

        // C / C++
        register(new CLikeHighlighter(
                Set.of("c", "cpp", "cc", "cxx", "h", "hpp"),
                Set.of(
                        "auto", "bool", "break", "case", "catch", "char", "class", "const",
                        "constexpr", "continue", "default", "delete", "do", "double",
                        "else", "enum", "explicit", "export", "extern", "false", "float",
                        "for", "friend", "goto", "if", "inline", "int", "long", "mutable",
                        "namespace", "new", "noexcept", "nullptr", "operator", "override",
                        "private", "protected", "public", "register", "reinterpret_cast",
                        "return", "short", "signed", "sizeof", "static", "static_assert",
                        "struct", "switch", "template", "this", "thread_local", "throw",
                        "true", "try", "typedef", "typeid", "typename", "union", "unsigned",
                        "using", "virtual", "void", "volatile", "while"
                )
        ));

        // Python
        register(new PythonHighlighter());

        // Bash / POSIX shell / Zsh (.sh, .bash, .zsh, .bashrc ...)
        register(new BashHighlighter());
    }

    public void register(SyntaxHighlighter highlighter) {
        highlighters.add(highlighter);
    }

    /**
     * Resolves the highlighter for the provided filename by inspecting its extension.
     */
    public SyntaxHighlighter forFileName(String fileName) {
        if (fileName == null) {
            return fallback;
        }

        // Look only at the last path component, so "my.dir/Makefile" is not treated as having extension "dir/Makefile"
        int slash = Math.max(fileName.lastIndexOf('/'), fileName.lastIndexOf('\\'));
        String baseName = fileName.substring(slash + 1);
        int dot = baseName.lastIndexOf('.');
        if (dot < 0) {
            return fallback;
        }

        String extension = baseName.substring(dot + 1);

        for (SyntaxHighlighter highlighter : highlighters) {
            if (highlighter.supportsExtension(extension)) {
                return highlighter;
            }
        }

        return fallback;
    }
}