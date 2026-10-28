package org.maghraby.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TextEditorTest {

    private TextEditor editor;

    @BeforeEach
    void setUp() {
        editor = new TextEditor();
    }

    @Test
    @DisplayName("Test text insertion and modification")
    void testInsertText() {
        editor.insertAt(0, 0, "Hello World\nSecond Line");
        assertEquals(2, editor.getLineCount());
        assertTrue(editor.isDirty());
    }

    @Test
    @DisplayName("Test text deletion")
    void testDeleteText() {
        editor.insertAt(0, 0, "Hello World");
        editor.deleteForward(0, 0);
        assertEquals("ello World", editor.getLine(0));
    }

    @Test
    @DisplayName("Test undo and redo logic")
    void testUndoRedoLogic() {
        int[] cursor = new int[]{0, 0};

        // Insert text
        editor.insertAt(0, 0, "Initial Text");
        assertEquals("Initial Text", editor.getLine(0));

        // Undo operation (passing cursor array)
        editor.undo(cursor);
        assertEquals("", editor.getLine(0).trim());

        // Redo operation
        editor.redo(cursor);
        assertEquals("Initial Text", editor.getLine(0).trim());
    }
}