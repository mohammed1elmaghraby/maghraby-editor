package org.maghraby;

import org.maghraby.ui.Renderer;
import org.maghraby.ui.Terminal;

import static org.maghraby.core.State.*;
import static org.maghraby.ui.Terminal.*;
import static org.maghraby.lsp.LspManager.*;
import static org.maghraby.input.Commands.*;
import static org.maghraby.input.Input.*;

/**
 * Terminal text editor (vim-like modes) with syntax highlighting, split panes, LSP and :run / :compile.
 * Requires a real terminal supporting ANSI escapes and `stty` (Linux, macOS, WSL, Git-Bash...).
 */
public class Main {
    public static void main(String[] args) {
        originalStty = stty("-g");
        if (originalStty == null) {
            System.err.println("Error: This editor requires a real terminal capable of 'stty' configuration.");
            System.exit(1);
            return;
        }

        if (args.length > 0 && !args[0].trim().isEmpty()) {
            docs[0].filename = args[0].trim();
            statusMessage = docs[0].editor.loadFromFile(docs[0].filename);
            startLsp(docs[0]); // background: never delays startup
        }
        refreshGitBranch();

        Runtime.getRuntime().addShutdownHook(new Thread(Terminal::restoreTerminal, "Terminal-Restore"));

        Thread renderThread = null;
        try {
            updateTerminalSize();
            enterEditorScreen();

            renderThread = new Thread(Renderer::renderLoop, "Render-Thread");
            renderThread.start();

            Thread resizeThread = new Thread(Terminal::resizeWatcher, "Resize-Watcher");
            resizeThread.setDaemon(true);
            resizeThread.start();

            inputLoop();
        } catch (Throwable t) {
            running = false;
            restoreTerminal();
            t.printStackTrace();
        } finally {
            running = false;
            if (renderThread != null) {
                try { renderThread.join(1000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            }
            restoreTerminal(); // give the screen back first, the language server shutdown may take a moment
            stopAllLsp();
        }
    }
}
