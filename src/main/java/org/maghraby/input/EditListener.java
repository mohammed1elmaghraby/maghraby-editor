package org.maghraby.input;

/**
 * Receives every document mutation as an LSP-style range, expressed in the coordinates that were
 * valid BEFORE the edit (exactly what textDocument/didChange wants). Lines and columns are 0-based;
 * columns count UTF-16 chars, which is what Java Strings use.
 *
 * Called while the editor's monitor is held, so implementations must only enqueue (never block,
 * never call back into the editor).
 */
public interface EditListener {
    void onInsert(int line, int col, String text);

    void onDelete(int startLine, int startCol, int endLine, int endCol);
}
