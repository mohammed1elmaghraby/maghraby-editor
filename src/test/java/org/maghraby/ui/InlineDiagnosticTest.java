package org.maghraby.ui;

import org.junit.jupiter.api.Test;
import org.maghraby.core.Doc;
import org.maghraby.core.State;
import org.maghraby.core.TextEditor;
import org.maghraby.lsp.LspClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class InlineDiagnosticTest {

    @Test
    void blendMixesTopOverBase() {
        int[] r = Ansi.blend(new int[]{200, 100, 0}, new int[]{0, 0, 100}, 0.5);
        assertArrayEquals(new int[]{100, 50, 50}, r);
        assertArrayEquals(new int[]{10, 20, 30}, Ansi.blend(new int[]{255, 255, 255}, new int[]{10, 20, 30}, 0));
    }

    @Test
    void messageIsDrawnOnTheSameRowAsTheProblem() {
        Terminal.termCols = 100;
        Terminal.termRows = 24;
        State.split = Split.NONE;
        State.focus = 0;
        State.inlineDiagnostics = true;
        TextEditor ed = new TextEditor();
        ed.loadInitialContent("int a = 1;\nint b = oops;\nint c = 3;\n");
        Doc doc = new Doc(ed, "Test.java");
        doc.diagnostics = List.of(new LspClient.Diagnostic(1, 8, 1, 12, 1, "cannot find symbol oops", "javac"));
        State.docs[0] = doc;
        State.cursorRow = 0;
        State.cursorCol = 0;

        String frame = Renderer.buildFrame();
        // Row 3 on screen is file line 1 (row 1 = tab line, row 2 = first line).
        int rowStart = frame.indexOf("\033[3;1H");
        int rowEnd = frame.indexOf("\033[4;1H");
        assertTrue(rowStart >= 0 && rowEnd > rowStart);
        String row = frame.substring(rowStart, rowEnd);
        assertTrue(row.contains("cannot find symbol oops"), "message must be on the problem's row");
        assertTrue(row.contains("\033[48;"), "blended background expected");
        // ...and not on the neighbouring lines.
        assertFalse(frame.substring(frame.indexOf("\033[2;1H"), rowStart).contains("cannot find symbol"));

        State.inlineDiagnostics = false;
        String off = Renderer.buildFrame();
        assertFalse(off.substring(off.indexOf("\033[3;1H"), off.indexOf("\033[4;1H")).contains("cannot find symbol"));
        State.inlineDiagnostics = true;
    }

    @Test
    void bandHasExactWidth() {
        LspClient.Diagnostic d = new LspClient.Diagnostic(0, 0, 0, 1, 2, "x".repeat(200), null);
        String s = Renderer.inlineDiagnostic(d, 30).replaceAll("\033\\[[0-9;]*m", "");
        assertEquals(30, s.length());
    }
}