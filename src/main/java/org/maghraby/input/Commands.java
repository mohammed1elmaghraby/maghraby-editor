package org.maghraby.input;

import org.maghraby.core.Doc;
import org.maghraby.core.TextEditor;
import org.maghraby.runner.CodeRunner;
import org.maghraby.ui.*;

import static org.maghraby.ui.Ansi.ANSI_RESET;
import static org.maghraby.ui.Ansi.ESC;
import static org.maghraby.ui.Panes.*;
import static org.maghraby.ui.Terminal.*;
import static org.maghraby.core.State.*;
import static org.maghraby.lsp.LspManager.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Editor commands (:w, :q, :run, :split ...), saving, running code and the git branch lookup.
 */
public final class Commands {
    private Commands() {}

    public static void tryQuit() {
        if (split != Split.NONE) {
            if (closePane(focus, false)) {
                statusMessage = "Pane closed.";
            } else {
                statusMessage = "Unsaved changes! Use :w to save, :wq to save+quit, or :q! to discard.";
            }
            return;
        }
        if (ed().isDirty()) {
            statusMessage = "Unsaved changes! :w to save, :wq to save+quit, :q! (or Ctrl+Q) to discard.";
        } else {
            running = false;
        }
    }

    public static boolean saveCommand(String arg) {
        String target = arg.isEmpty() ? D().filename : arg;
        String msg = ed().saveToFile(target);
        statusMessage = msg;
        boolean ok = msg.startsWith("Saved");
        if (ok) {
            boolean renamed = !target.equals(D().filename);
            D().filename = target;
            refreshGitBranch();
            if (renamed) startLsp(D()); // a new name can mean a different language (Untitled.txt -> a.go)
        }
        return ok;
    }

    public static void executeCommand(String cmdLine) {
        if (cmdLine.isEmpty()) {
            statusMessage = "NORMAL MODE";
            return;
        }
        String[] parts = cmdLine.split("\\s+", 2);
        String name = parts[0];
        String arg = parts.length > 1 ? parts[1].trim() : "";

        switch (name) {
            case "q": case "quit": case "exit": tryQuit(); break;
            case "q!": case "quit!":
                if (split != Split.NONE) closePane(focus, true);
                else running = false;
                break;
            case "w": case "write": saveCommand(arg); break;
            case "wq": case "x":
                if (saveCommand(arg)) {
                    if (split != Split.NONE) closePane(focus, true);
                    else running = false;
                }
                break;
            case "run": runCommand(true, arg); break;
            case "compile": case "build": runCommand(false, arg); break;
            case "lsp":
                if (arg.equals("stop")) {
                    stopLsp(D(), false);
                    statusMessage = "LSP stopped.";
                } else {
                    startLsp(D());
                }
                break;
            case "split": case "sp": openSplit(Split.HORIZONTAL, arg); break;
            case "vsplit": case "vs": case "vsp": openSplit(Split.VERTICAL, arg); break;
            case "only": case "on":
                if (split == Split.NONE) statusMessage = "Only one pane.";
                else if (closePane(1 - focus, false)) statusMessage = "Single pane.";
                break;
            case "set":
                if (arg.equals("rnu") || arg.equals("relativenumber")) {
                    relativeNumbers = true;
                    statusMessage = "Relative line numbers on.";
                } else if (arg.equals("nornu") || arg.equals("norelativenumber")) {
                    relativeNumbers = false;
                    statusMessage = "Relative line numbers off.";
                } else if (arg.equals("icons") || arg.equals("nerdicons")) {
                    nerdIcons = true;
                    needsRedraw = true;
                    statusMessage = "Nerd Font icons on (needs a Nerd Font in your terminal).";
                } else if (arg.equals("noicons") || arg.equals("nonerdicons")) {
                    nerdIcons = false;
                    needsRedraw = true;
                    statusMessage = "Nerd Font icons off.";
                } else if (arg.equals("autopair") || arg.equals("pairs")) {
                    autoPairs = true;
                    statusMessage = "Auto-closing pairs on.";
                } else if (arg.equals("noautopair") || arg.equals("nopairs")) {
                    autoPairs = false;
                    statusMessage = "Auto-closing pairs off.";
                } else {
                    statusMessage = "Unknown option: " + arg + "   (rnu, nornu, icons, noicons, autopair, noautopair)";
                }
                break;
            case "e": case "edit": openFile(arg, false); break;
            case "e!": case "edit!": openFile(arg, true); break;
            case "tree": case "explorer":
                if (arg.isEmpty()) {
                    Sidebar.toggle();
                } else {
                    Path dir = normPath(arg);
                    if (dir != null && Files.isDirectory(dir)) Sidebar.show(dir);
                    else statusMessage = "Not a directory: " + arg;
                }
                break;
            case "goto": case "go": case "g": gotoLine(arg); break;
            case "help": case "h": statusMessage = HELP; break;
            default:
                // vim style: ":584" or ":$" jumps straight to a line
                if (name.equals("$") || name.matches("\\d+")) gotoLine(name);
                else statusMessage = "Unknown command: " + cmdLine + "   (try :help)";
        }
    }

    /**
     * :e [file] / :e! [file]. Replaces the document of the focused pane with another file (a new empty
     * buffer when it does not exist yet). {@code :e!} discards unsaved changes, and with no name it
     * reloads the current file. A directory opens the file tree there. Returns true when a file was opened.
     */
    public static boolean openFile(String arg, boolean force) {
        String name = arg.trim();
        boolean reload = false;
        if (name.isEmpty()) {
            if (!force) {
                statusMessage = "Usage: :e <file>    (:e! discards unsaved changes, :tree opens the file tree)";
                return false;
            }
            name = D().filename;
            reload = true;
        }
        Path path = normPath(name);
        if (path != null && Files.isDirectory(path)) { // ":e ." opens the tree there
            Sidebar.show(path);
            return false;
        }

        Doc cur = D();
        boolean sharedWithOther = split != Split.NONE && docs[1 - focus] == cur;
        if (!reload && path != null && path.equals(normPath(cur.filename))) {
            statusMessage = "Already editing " + name;
            return true;
        }
        if (!force && !sharedWithOther && cur.editor.isDirty()) {
            statusMessage = "Unsaved changes! :w to save, or :e! " + name + " to discard them.";
            return false;
        }

        Doc target = (!reload && split != Split.NONE) ? findOpenDoc(name) : null; // the other pane has it: share it
        String loadMsg = null;
        if (target == null) {
            TextEditor e = new TextEditor();
            loadMsg = e.loadFromFile(name);
            if (loadMsg.startsWith("Error")) {
                statusMessage = loadMsg;
                return false;
            }
            target = new Doc(e, name);
        }

        if (showingSuggestions) Autocomplete.closeSuggestions();
        if (!sharedWithOther) stopLsp(cur, false);
        docs[focus] = target;
        cursorRow = 0;
        cursorCol = 0;
        goalCol = 0;
        paneTop[focus] = 0;
        paneLeft[focus] = 0;
        forceClear.set(true);
        if (loadMsg != null) startLsp(target);
        refreshGitBranch();
        statusMessage = loadMsg != null ? loadMsg : "Opened " + name + " (also shown in the other pane).";
        return true;
    }

    /** Moves the cursor to a 1-based line number ("$" = last line). Out-of-range numbers are clamped. */
    public static void gotoLine(String arg) {
        int count = ed().getLineCount();
        int target;
        if (arg.equals("$")) {
            target = count;
        } else {
            try {
                target = Integer.parseInt(arg.trim());
            } catch (NumberFormatException e) {
                statusMessage = "Usage: :<line>  or  :goto <line>  (:$ = last line)";
                return;
            }
        }
        int line = Math.max(1, Math.min(target, count));
        cursorRow = line - 1;
        cursorCol = 0;
        goalCol = 0;
        statusMessage = line == target ? "Line " + line : "Line " + line + " (last line is " + count + ")";
    }

    /** Saves, builds, and optionally runs the file while handing over terminal access. */
    public static void runCommand(boolean run, String argLine) {
        String file = D().filename;
        if (CodeRunner.detect(file) == null) {
            statusMessage = "Can't " + (run ? "run" : "compile") + " '" + file + "'. Supported: "
                    + CodeRunner.supportedExtensions() + "  (rename with :w name.ext)";
            return;
        }
        String saved = ed().saveToFile(file);
        if (!saved.startsWith("Saved")) {
            statusMessage = saved;
            return;
        }

        CodeRunner.Plan plan;
        try {
            plan = CodeRunner.plan(file, run, run ? splitArgs(argLine) : new ArrayList<String>());
        } catch (CodeRunner.RunnerException e) {
            statusMessage = e.getMessage();
            return;
        }

        String summary;
        suspendTerminal();
        try {
            summary = CodeRunner.execute(plan, out);
            out.print("\n" + ESC + "[7m Press Enter to return to the editor " + ANSI_RESET);
            out.flush();
            waitForEnter();
        } finally {
            CodeRunner.cleanup(plan);
            resumeTerminal();
        }
        statusMessage = summary;
    }

    public static void waitForEnter() {
        try {
            int ch;
            while ((ch = System.in.read()) != -1 && ch != '\n') { /* Swallow keys until enter */ }
            if (ch == -1) running = false;
        } catch (IOException ignored) {
            running = false;
        }
    }

    public static List<String> splitArgs(String line) {
        List<String> args = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        char quote = 0;
        boolean has = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quote != 0) {
                if (c == quote) quote = 0; else cur.append(c);
            } else if (c == '"' || c == '\'') {
                quote = c;
                has = true;
            } else if (Character.isWhitespace(c)) {
                if (has || cur.length() > 0) { args.add(cur.toString()); cur.setLength(0); has = false; }
            } else {
                cur.append(c);
            }
        }
        if (has || cur.length() > 0) args.add(cur.toString());
        return args;
    }

    /** Reads the current git branch (or short commit id) by looking for .git/HEAD above the open file. */
    public static void refreshGitBranch() {
        String result = "";
        try {
            Path file = Paths.get(D().filename.trim()).toAbsolutePath().normalize();
            for (Path p = file.getParent(); p != null; p = p.getParent()) {
                Path head = p.resolve(".git").resolve("HEAD");
                if (Files.isRegularFile(head)) {
                    String h = new String(Files.readAllBytes(head), StandardCharsets.UTF_8).trim();
                    result = h.startsWith("ref:") ? h.substring(h.lastIndexOf('/') + 1)
                            : h.substring(0, Math.min(7, h.length()));
                    break;
                }
            }
        } catch (IOException | RuntimeException ignored) { }
        gitBranch = result;
    }
}