package org.maghraby.lsp;

import org.maghraby.runner.CodeRunner;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Maps a file extension to a language id and a language server command found on this machine. */
final class LspServers {
    private LspServers() {}

    public static final class Spec {
        final String languageId;
        final List<String> command;

        Spec(String languageId, List<String> command) {
            this.languageId = languageId;
            this.command = command;
        }
    }

    public static String languageId(String filename) {
        String ext = extension(filename);
        switch (ext) {
            case "go": return "go";
            case "java": return "java";
            case "c": case "h": return "c";
            case "cpp": case "cc": case "cxx": case "hpp": case "hh": return "cpp";
            case "py": return "python";
            default: return null;
        }
    }

    /** Returns null when the language is unknown or no server is installed. */
    public static Spec forFile(String filename, Path rootDir) {
        String lang = languageId(filename);
        if (lang == null) return null;

        List<String> cmd = null;
        switch (lang) {
            case "go": {
                String t = CodeRunner.findTool("gopls");
                if (t == null) {
                    File f = new File(System.getProperty("user.home", ""), "go/bin/gopls");
                    if (f.isFile() && f.canExecute()) t = f.getAbsolutePath();
                }
                cmd = t == null ? null : list(t);
                break;
            }
            case "c":
            case "cpp": {
                String t = CodeRunner.findTool("clangd");
                cmd = t == null ? null : list(t);
                break;
            }
            case "python": {
                String pyright = CodeRunner.findTool("pyright-langserver");
                if (pyright != null) {
                    cmd = list(pyright, "--stdio");
                } else {
                    String pylsp = CodeRunner.findTool("pylsp");
                    cmd = pylsp == null ? null : list(pylsp);
                }
                break;
            }
            case "java": {
                String t = CodeRunner.findTool("jdtls");
                if (t != null) cmd = list(t, "-data", jdtlsDataDir(rootDir).toString());
                break;
            }
            default:
                break;
        }
        return cmd == null ? null : new Spec(lang, cmd);
    }

    /** jdtls needs a private workspace directory per project. */
    private static Path jdtlsDataDir(Path rootDir) {
        Path dir = Paths.get(System.getProperty("user.home", "."), ".cache", "maghraby-editor", "jdtls",
                Integer.toHexString(rootDir.toString().hashCode()));
        try {
            Files.createDirectories(dir);
        } catch (IOException ignored) { }
        return dir;
    }

    private static List<String> list(String... parts) {
        List<String> l = new ArrayList<>();
        for (String p : parts) l.add(p);
        return l;
    }

    private static String extension(String filename) {
        if (filename == null) return "";
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
