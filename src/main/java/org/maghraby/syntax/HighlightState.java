package org.maghraby.syntax;

/**
 * Tracks parser state across lines to maintain multi-line token integrity.
 *
 * The state cache stores each state as one byte (its ordinal), so keep this enum under 128 values.
 */
public enum HighlightState {
    NORMAL,
    C_BLOCK_COMMENT,
    C_DOC_COMMENT,
    C_TEXT_BLOCK,
    PY_TRIPLE_DOUBLE,
    PY_TRIPLE_SINGLE,
    /** Inside a double-quoted shell string that continues over several lines. */
    BASH_DQ,
    /** Inside a single-quoted shell string that continues over several lines. */
    BASH_SQ,
    /** Inside a here-document body (ends at a delimiter-looking line, see BashHighlighter). */
    BASH_HEREDOC,
    /** Previous line ended with a backslash while a command name was still expected (after | or &&). */
    BASH_CONT_CMD,
    /** Previous line ended with a backslash in the middle of a command's arguments. */
    BASH_CONT_ARG
}
