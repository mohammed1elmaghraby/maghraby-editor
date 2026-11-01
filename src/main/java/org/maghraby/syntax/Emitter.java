package org.maghraby.syntax;

/**
 * Builds one ANSI-coloured line. Consecutive tokens with the same colour share one escape sequence,
 * and the line always ends with a reset. The visible text is never changed, only colour codes are added.
 */
final class Emitter {
    private final StringBuilder sb;
    private String current = Theme.RESET;

    Emitter(int expectedLength) {
        sb = new StringBuilder(expectedLength + expectedLength / 2 + 16);
    }

    /** Appends {@code text} in {@code color}. Text may carry its own colour codes if it ends in {@code color}. */
    void put(String color, String text) {
        if (text.isEmpty()) return;
        if (!color.equals(current)) {
            sb.append(color);
            current = color;
        }
        sb.append(text);
    }

    String finish() {
        if (!current.equals(Theme.RESET)) sb.append(Theme.RESET);
        return sb.toString();
    }
}
