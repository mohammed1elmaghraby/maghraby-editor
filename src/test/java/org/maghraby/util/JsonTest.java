package org.maghraby.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JsonTest {

    @Test
    @DisplayName("Test JSON parsing and value extraction")
    void testJsonParsing() {
        String jsonInput = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"value\":\"success\"}}";

        Object parsed = Json.parse(jsonInput);
        assertNotNull(parsed, "Parsed object should not be null");
    }

    @Test
    @DisplayName("Test handling malformed JSON")
    void testMalformedJson() {
        String invalidJson = "{\"key\": incomplete}";
        assertThrows(Exception.class, () -> Json.parse(invalidJson));
    }
}