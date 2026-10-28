package org.maghraby.ui;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.maghraby.core.Doc;
import org.maghraby.core.State;
import org.maghraby.core.TextEditor;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class RenderSmokeTest {

    @BeforeEach
    void setUp() {
        // Set default terminal dimensions
        Terminal.termCols = 80;
        Terminal.termRows = 24;

        // Initialize documents in State with TextEditor and filename
        if (State.docs == null) {
            State.docs = new Doc[]{
                    new Doc(new TextEditor(), "Untitled.txt"),
                    new Doc(new TextEditor(), "Untitled2.txt")
            };
        }
        for (int i = 0; i < State.docs.length; i++) {
            if (State.docs[i] == null) {
                State.docs[i] = new Doc(new TextEditor(), "Untitled" + (i + 1) + ".txt");
            }
        }
    }

    @ParameterizedTest
    @EnumSource(Split.class)
    @DisplayName("Smoke Test: Render frame across all split modes without throwing exceptions")
    void testRenderNoExceptionsForAllSplitModes(Split mode) {
        assertDoesNotThrow(() -> {
            State.split = mode;
            State.focus = 0;

            // Build main frame
            Renderer.buildFrame();

            if (mode != Split.NONE) {
                State.focus = 1;
                Renderer.buildFrame();
            }
        }, "Exception occurred during rendering in split mode: " + mode);
    }
}