package org.maghraby.input;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.maghraby.core.State;
import org.maghraby.lsp.LspDocumentSync;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AutocompleteTest {

    @Test
    @DisplayName("Test fetching keywords by file language")
    void testKeywordsForLanguage() {
        String[] javaKeywords = Autocomplete.keywordsFor("Main.java");
        assertTrue(javaKeywords.length > 0);
        assertTrue(List.of(javaKeywords).contains("public"));

        String[] pyKeywords = Autocomplete.keywordsFor("script.py");
        assertTrue(pyKeywords.length > 0);
        assertTrue(List.of(pyKeywords).contains("def"));
    }

    @Test
    @DisplayName("Test local completion suggestions for a prefix")
    void testLocalSuggestions() {
        // Set file type in global state to load Java keywords
        State.D().filename = "Test.java";

        List<LspDocumentSync.Completion> suggestions = Autocomplete.localSuggestions("pub");

        assertNotNull(suggestions);
        assertFalse(suggestions.isEmpty(), "Suggestions should contain matching results for 'pub'");

        boolean containsPublic = suggestions.stream()
                .anyMatch(c -> "public".equals(c.insertText));
        assertTrue(containsPublic, "Suggestions should contain the keyword 'public'");
    }

    @Test
    @DisplayName("Test empty suggestions for unmatched prefix")
    void testEmptySuggestions() {
        State.D().filename = "Test.java";

        List<LspDocumentSync.Completion> suggestions = Autocomplete.localSuggestions("xyzUnmatchedKey");
        assertTrue(suggestions.isEmpty());
    }
}