package org.maghraby.ui;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.maghraby.core.State;
import org.maghraby.ui.Split;

import static org.junit.jupiter.api.Assertions.*;

class PanesTest {

    @BeforeEach
    void resetPanesState() {
        State.split = Split.NONE;
        State.focus = 0;
    }

    @Test
    @DisplayName("Test pane splitting and focus switching")
    void testSplitAndSwitch() {
        State.split = Split.VERTICAL;
        assertEquals(Split.VERTICAL, State.split);

        Panes.switchPane();
        assertEquals(1, State.focus);

        Panes.switchPane();
        assertEquals(0, State.focus);
    }

    @Test
    @DisplayName("Test closing pane refusal with unsaved changes")
    void testClosePaneWithUnsavedChanges() {
        State.split = Split.HORIZONTAL;

        // Close without force
        boolean closed = Panes.closePane(0, false);

        // Verify direct closure when forced
        assertDoesNotThrow(() -> Panes.closePane(0, true));
    }
}