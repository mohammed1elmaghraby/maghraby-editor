package org.maghraby.core;

import org.maghraby.input.EditListener;
import org.maghraby.ds.EditAction;
import org.maghraby.ds.MyStack;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Text buffer + undo/redo history built on a Piece Table.
 *
 * The original file is kept read-only and every keystroke is appended to an add-buffer; the
 * document is just an ordered list of {@link Piece}s pointing into those two buffers.
 *
 * On top of that sits a <b>line index</b> ({@code lineStarts}): the document offset at which each
 * line begins. It is updated incrementally by every insert/delete, so row/column lookups,
 * line counts and line reads never rebuild or split the whole document.
 *
 * Costs per edit: finding the piece is O(pieces) (kept small by coalescing); updating the line
 * index is a binary search plus an array shift (O(lines) memmove, no allocation per line).
 * Neither depends on the file's size in characters the way "join everything, then split" did.
 */
public class TextEditor {
    private final MyStack undoStack;
    private final MyStack redoStack;
    private boolean dirty = false;

    // --- Piece Table Core Components ---
    String originalBuffer;      // Read-only buffer loaded from the file
    StringBuilder addBuffer;    // Append-only buffer for new user inputs
    List<Piece> pieces;         // The sequence of pieces linking buffers together

    // --- Document metrics kept in sync by insertText / deleteText ---
    private int docLength;                 // total characters in the document
    // Line index. The real start of line i is ls(i): entries at index >= deltaFrom are stored without
    // the pending shift `delta`. Typing on one line only changes `delta` (O(1)) instead of rewriting every
    // later line start, so editing in the middle of a multi-million-line file stays cheap.
    private int[] lineStarts = new int[16];
    private int lineCount = 1;             // number of valid entries in lineStarts (always >= 1)
    private int deltaFrom = 1;             // first index whose stored value still lacks `delta`
    private int delta = 0;

    // Lowest line touched by any edit since the last takeDirtyFromLine(); lets the renderer keep
    // per-line caches (e.g. syntax-highlighter state) valid for everything above the edit.
    private int dirtyFromLine = Integer.MAX_VALUE;

    // Optional observer of every mutation (used by the LSP document sync). It is called from inside
    // insertText/deleteText while this monitor is held, so it must only enqueue and return.
    private EditListener changeListener;

    public synchronized void setChangeListener(EditListener listener) {
        this.changeListener = listener;
    }

    /** Detaches `listener` only if it is still the active one (a newer listener may have replaced it). */
    public synchronized void removeChangeListener(EditListener listener) {
        if (this.changeListener == listener) this.changeListener = null;
    }

    public TextEditor() {
        this.undoStack = new MyStack();
        this.redoStack = new MyStack();
        loadInitialContent(""); // Initialize an empty Piece Table
    }

    // ------------------------------------------------------------------
    // Piece Table Logic & Helpers
    // ------------------------------------------------------------------

    public synchronized void loadInitialContent(String fileContent) {
        this.originalBuffer = fileContent != null ? fileContent : "";
        this.addBuffer = new StringBuilder();
        this.pieces = new ArrayList<>();

        if (!originalBuffer.isEmpty()) {
            pieces.add(newPiece(BufferType.ORIGINAL, 0, originalBuffer.length()));
        }
        this.docLength = originalBuffer.length();
        rebuildLineIndex();
        dirtyFromLine = 0; // everything changed
    }

    /**
     * Returns the lowest line number modified since the previous call (Integer.MAX_VALUE if nothing
     * changed) and resets the marker. Line numbers below the result are guaranteed untouched, and the
     * text of the returned line itself may have changed only from the edit point onwards, so anything
     * derived from lines [0, result) -- such as the highlighter state at the start of line `result` --
     * is still valid. Intended for a single consumer (the render thread).
     */
    public synchronized int takeDirtyFromLine() {
        int v = dirtyFromLine;
        dirtyFromLine = Integer.MAX_VALUE;
        return v;
    }

    private static Piece newPiece(BufferType source, int start, int length) {
        Piece p = new Piece();
        p.source = source;
        p.start = start;
        p.length = length;
        return p;
    }

    public synchronized String getFullText() {
        StringBuilder sb = new StringBuilder(docLength);
        appendRange(sb, 0, docLength);
        return sb.toString();
    }

    /** Appends document characters [from, to) to sb by walking only the pieces that overlap it. */
    private void appendRange(StringBuilder sb, int from, int to) {
        if (from >= to) return;
        int pos = 0;
        for (Piece p : pieces) {
            int pEnd = pos + p.length;
            if (pEnd > from && pos < to) {
                int s = p.start + Math.max(from, pos) - pos;
                int e = p.start + Math.min(to, pEnd) - pos;
                if (p.source == BufferType.ORIGINAL) sb.append(originalBuffer, s, e);
                else sb.append(addBuffer, s, e);
            }
            pos = pEnd;
            if (pos >= to) break;
        }
    }

    private String substring(int from, int to) {
        from = Math.max(0, from);
        to = Math.min(to, docLength);
        if (from >= to) return "";
        StringBuilder sb = new StringBuilder(to - from);
        appendRange(sb, from, to);
        return sb.toString();
    }

    private char charAt(int offset) {
        int pos = 0;
        for (Piece p : pieces) {
            if (offset < pos + p.length) {
                int i = p.start + offset - pos;
                return p.source == BufferType.ORIGINAL ? originalBuffer.charAt(i) : addBuffer.charAt(i);
            }
            pos += p.length;
        }
        throw new IndexOutOfBoundsException("offset " + offset + " >= length " + docLength);
    }

    // ------------------------------------------------------------------
    // Line Index
    // ------------------------------------------------------------------

    /** Real offset where line i begins. */
    private int ls(int i) {
        return i >= deltaFrom ? lineStarts[i] + delta : lineStarts[i];
    }

    /** Rebuilds lineStarts from scratch with one pass over the original buffer (used on load only). */
    private void rebuildLineIndex() {
        lineStarts = new int[1024];
        lineCount = 1;
        lineStarts[0] = 0;
        deltaFrom = 1;
        delta = 0;
        final String text = originalBuffer;
        int i = 0;
        while ((i = text.indexOf('\n', i)) >= 0) {
            i++;
            if (lineCount == lineStarts.length) lineStarts = Arrays.copyOf(lineStarts, lineCount * 2);
            lineStarts[lineCount++] = i;
        }
        deltaFrom = lineCount;
    }

    /** Number of line starts that are <= value (i.e. the index of the first start greater than it). */
    private int upperBound(int value) {
        int lo = 0, hi = lineCount;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (ls(mid) <= value) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }

    private void ensureLineCapacity(int needed) {
        if (needed > lineStarts.length) {
            lineStarts = Arrays.copyOf(lineStarts, Math.max(needed, lineStarts.length * 2));
        }
    }

    /** Makes the pending shift apply to exactly the entries at index >= idx (cost: |idx - deltaFrom|). */
    private void moveDeltaTo(int idx) {
        if (deltaFrom > lineCount) deltaFrom = lineCount;
        if (idx < deltaFrom) {
            for (int k = idx; k < deltaFrom; k++) lineStarts[k] -= delta;
        } else {
            for (int k = deltaFrom; k < idx; k++) lineStarts[k] += delta;
        }
        deltaFrom = idx;
        if (deltaFrom >= lineCount) delta = 0; // nothing left that the shift applies to
    }

    /** Text of {@code text} was just inserted at {@code offset}: shift later lines and add new ones. */
    private void indexInsert(int offset, String text) {
        int n = text.length();
        int newlines = 0;
        for (int i = 0; i < n; i++) if (text.charAt(i) == '\n') newlines++;

        int idx = upperBound(offset); // first line start strictly after the insertion point
        moveDeltaTo(idx);
        if (newlines > 0) {
            ensureLineCapacity(lineCount + newlines);
            System.arraycopy(lineStarts, idx, lineStarts, idx + newlines, lineCount - idx);
            int w = idx;
            for (int j = 0; j < n; j++) {
                if (text.charAt(j) == '\n') lineStarts[w++] = offset + j + 1; // real values, below deltaFrom
            }
            lineCount += newlines;
        }
        deltaFrom = idx + newlines;
        delta += n;                      // every later line start moves right by n
        if (deltaFrom >= lineCount) delta = 0;
    }

    /** Characters [offset, offset + len) were just removed: drop their line starts, shift the rest. */
    private void indexDelete(int offset, int len) {
        int lo = upperBound(offset);       // first start > offset
        int hi = upperBound(offset + len); // first start > end  (starts in (offset, end] were deleted)
        int removed = hi - lo;
        moveDeltaTo(hi);
        if (removed > 0) {
            System.arraycopy(lineStarts, hi, lineStarts, lo, lineCount - hi);
            lineCount -= removed;
        }
        deltaFrom = lo;
        delta -= len;
        if (deltaFrom >= lineCount) delta = 0;
    }

    private int clampRow(int row) {
        return Math.max(0, Math.min(row, lineCount - 1));
    }

    /** Offset just past the last character of the line (excluding its '\n'). */
    private int lineEnd(int row) {
        return row + 1 < lineCount ? ls(row + 1) - 1 : docLength;
    }

    private int lineLength(int row) {
        return lineEnd(row) - ls(row);
    }

    private int getOffset(int row, int col) {
        row = clampRow(row);
        return ls(row) + Math.max(0, Math.min(col, lineLength(row)));
    }

    private int[] calculateNewPosition(int row, int col, String text) {
        int newlines = 0;
        int lastNewlineIdx = -1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                newlines++;
                lastNewlineIdx = i;
            }
        }
        if (newlines == 0) {
            return new int[]{row, col + text.length()};
        } else {
            return new int[]{row + newlines, text.length() - lastNewlineIdx - 1};
        }
    }

    /** Test hook: throws if the incremental line index disagrees with the actual text. */
    synchronized void verifyIndex() {
        String text = getFullText();
        if (text.length() != docLength) {
            throw new IllegalStateException("docLength " + docLength + " != text length " + text.length());
        }
        int expectedLines = 1;
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) == '\n') expectedLines++;
        if (expectedLines != lineCount) {
            throw new IllegalStateException("lineCount " + lineCount + " != expected " + expectedLines);
        }
        int row = 1;
        if (ls(0) != 0) throw new IllegalStateException("ls(0) != 0");
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                if (ls(row) != i + 1) {
                    throw new IllegalStateException("ls(" + row + ")=" + ls(row) + " expected " + (i + 1));
                }
                row++;
            }
        }
    }

    // ------------------------------------------------------------------
    // Read Access
    // ------------------------------------------------------------------

    public synchronized int getLineCount() {
        return lineCount;
    }

    public synchronized String getLine(int index) {
        if (index < 0 || index >= lineCount) return "";
        return substring(ls(index), lineEnd(index));
    }

    public synchronized int getLineLength(int index) {
        if (index < 0 || index >= lineCount) return 0;
        return lineLength(index);
    }

    /** Lines [from, to), clamped to the document. One pass over the pieces, then one split. */
    public synchronized List<String> getLines(int from, int to) {
        from = Math.max(0, from);
        to = Math.min(to, lineCount);
        if (from >= to) return new ArrayList<>();
        String block = substring(ls(from), lineEnd(to - 1));
        // Exactly (to - from - 1) newlines in block, so split(-1) yields exactly (to - from) lines.
        return new ArrayList<>(Arrays.asList(block.split("\n", -1)));
    }

    public synchronized boolean isDirty() {
        return dirty;
    }

    // ------------------------------------------------------------------
    // Core Modifiers
    // ------------------------------------------------------------------

    public synchronized void insertText(int offset, String newText) {
        if (newText == null || newText.isEmpty()) return;
        offset = Math.max(0, Math.min(offset, docLength));

        if (changeListener != null) { // position BEFORE the edit, as LSP expects
            int line = upperBound(offset) - 1;
            changeListener.onInsert(line, offset - ls(line), newText);
        }

        int n = newText.length();
        int addStart = addBuffer.length();
        addBuffer.append(newText);

        boolean done = false;
        int pos = 0;
        for (int i = 0; i < pieces.size(); i++) {
            Piece p = pieces.get(i);

            if (offset <= pos + p.length) {
                int split = offset - pos;

                if (split == p.length) {
                    // Inserting right after this piece.
                    if (p.source == BufferType.ADD && p.start + p.length == addStart) {
                        // Coalesce: this piece already ends exactly where the new text begins in the
                        // add-buffer, so typing continues the same piece instead of creating a new one.
                        p.length += n;
                    } else {
                        pieces.add(i + 1, newPiece(BufferType.ADD, addStart, n));
                    }
                } else if (split == 0) {
                    pieces.add(i, newPiece(BufferType.ADD, addStart, n));
                } else {
                    // Inserting inside a piece: shrink it to the left half and add [new][right half].
                    Piece right = newPiece(p.source, p.start + split, p.length - split);
                    p.length = split;
                    pieces.add(i + 1, newPiece(BufferType.ADD, addStart, n));
                    pieces.add(i + 2, right);
                }
                done = true;
                break;
            }
            pos += p.length;
        }

        // Empty piece list (everything was deleted): the new text becomes the only piece.
        if (!done) pieces.add(newPiece(BufferType.ADD, addStart, n));

        dirtyFromLine = Math.min(dirtyFromLine, upperBound(offset) - 1); // line containing the edit point
        docLength += n;
        indexInsert(offset, newText);
    }

    /**
     * Removes [offset, offset + deleteLength) from the document. The range may span any number of
     * pieces: every overlapped piece is trimmed, split or dropped until the range is consumed.
     */
    public synchronized void deleteText(int offset, int deleteLength) {
        if (offset < 0 || deleteLength <= 0 || pieces == null) return;
        int end = (int) Math.min((long) offset + deleteLength, docLength);
        if (offset >= end) return;
        int totalRemoved = end - offset;

        if (changeListener != null) { // range BEFORE the edit, as LSP expects
            int sl = upperBound(offset) - 1;
            int el = upperBound(end) - 1;
            changeListener.onDelete(sl, offset - ls(sl), el, end - ls(el));
        }

        int pos = 0;
        int i = 0;
        while (i < pieces.size() && pos < end) {
            Piece p = pieces.get(i);
            int plen = p.length;
            int pEnd = pos + plen;

            if (pEnd <= offset) { // entirely before the range
                pos = pEnd;
                i++;
                continue;
            }

            int leftLen = Math.max(0, offset - pos);   // part of this piece kept on the left
            int cutEnd = Math.min(plen, end - pos);    // where the kept right part starts
            int rightLen = plen - cutEnd;              // part of this piece kept on the right

            if (leftLen > 0 && rightLen > 0) {
                // The range is strictly inside this piece: split it in two.
                Piece right = newPiece(p.source, p.start + cutEnd, rightLen);
                p.length = leftLen;
                pieces.add(i + 1, right);
                i += 2;
            } else if (leftLen > 0) {
                // Tail of the piece removed.
                releaseAddTail(p, plen - leftLen);
                p.length = leftLen;
                i++;
            } else if (rightLen > 0) {
                // Head of the piece removed.
                p.start += cutEnd;
                p.length = rightLen;
                i++;
            } else {
                // Whole piece removed.
                releaseAddTail(p, plen);
                pieces.remove(i);
            }
            pos = pEnd;
        }

        dirtyFromLine = Math.min(dirtyFromLine, upperBound(offset) - 1); // line containing the first removed char
        docLength -= totalRemoved;
        indexDelete(offset, totalRemoved);
    }

    /**
     * If the last {@code count} characters of this ADD piece are also the last characters of the
     * add-buffer, give them back. Nothing else references that region (pieces never overlap), and
     * undo/redo store text, not buffer positions, so this is safe. It keeps type/backspace cycles
     * from growing the buffer and lets the next keystroke coalesce into the same piece.
     */
    private void releaseAddTail(Piece p, int count) {
        if (p.source == BufferType.ADD && p.start + p.length == addBuffer.length()) {
            addBuffer.setLength(addBuffer.length() - count);
        }
    }

    // ------------------------------------------------------------------
    // User Actions
    // ------------------------------------------------------------------

    public synchronized int[] insertAt(int row, int col, String text) {
        if (text == null || text.isEmpty()) return new int[]{row, col};

        row = clampRow(row);
        col = Math.max(0, Math.min(col, lineLength(row)));

        undoStack.push(new EditAction(EditAction.Type.INSERT, row, col, text));
        redoStack.clear();
        dirty = true;

        insertText(ls(row) + col, text);

        return calculateNewPosition(row, col, text);
    }

    public synchronized int[] deleteAt(int row, int col) {
        if (row < 0 || row >= lineCount) return new int[]{row, col};
        int base = ls(row);
        int len = lineLength(row);
        col = Math.max(0, Math.min(col, len));

        if (col > 0) {
            int start = col - 1;
            if (start > 0 && Character.isLowSurrogate(charAt(base + start))
                    && Character.isHighSurrogate(charAt(base + start - 1))) {
                start--;
            }
            String removed = substring(base + start, base + col);

            undoStack.push(new EditAction(EditAction.Type.DELETE, row, start, removed));
            redoStack.clear();
            dirty = true;

            deleteText(base + start, removed.length());

            return new int[]{row, start};
        }

        if (row == 0) return new int[]{0, 0};

        int prevRow = row - 1;
        int prevCol = lineLength(prevRow);

        undoStack.push(new EditAction(EditAction.Type.DELETE, prevRow, prevCol, "\n"));
        redoStack.clear();
        dirty = true;

        deleteText(base - 1, 1); // the '\n' that ends the previous line

        return new int[]{prevRow, prevCol};
    }

    public synchronized int[] deleteForward(int row, int col) {
        if (row < 0 || row >= lineCount) return new int[]{row, col};
        int base = ls(row);
        int len = lineLength(row);
        col = Math.max(0, Math.min(col, len));

        String removed;
        if (col < len) {
            int end = col + 1;
            if (end < len && Character.isHighSurrogate(charAt(base + col))
                    && Character.isLowSurrogate(charAt(base + end))) {
                end++;
            }
            removed = substring(base + col, base + end);
        } else if (row + 1 < lineCount) {
            removed = "\n";
        } else {
            return new int[]{row, col};
        }

        undoStack.push(new EditAction(EditAction.Type.DELETE, row, col, removed, true));
        redoStack.clear();
        dirty = true;

        deleteText(base + col, removed.length());

        return new int[]{row, col};
    }

    // ------------------------------------------------------------------
    // History
    // ------------------------------------------------------------------

    public synchronized String undo(int[] cursor) {
        if (undoStack.isEmpty()) return "Nothing to undo!";
        EditAction action = undoStack.pop();
        redoStack.push(action);
        dirty = true;

        if (action.type == EditAction.Type.INSERT) {
            int offset = getOffset(action.row, action.col);
            deleteText(offset, action.text.length());
            cursor[0] = action.row;
            cursor[1] = action.col;
        } else {
            int offset = getOffset(action.row, action.col);
            insertText(offset, action.text);
            if (action.forward) {
                cursor[0] = action.row;
                cursor[1] = action.col;
            } else {
                int[] pos = calculateNewPosition(action.row, action.col, action.text);
                cursor[0] = pos[0];
                cursor[1] = pos[1];
            }
        }
        return "Undone.";
    }

    public synchronized String redo(int[] cursor) {
        if (redoStack.isEmpty()) return "Nothing to redo!";
        EditAction action = redoStack.pop();
        undoStack.push(action);
        dirty = true;

        if (action.type == EditAction.Type.INSERT) {
            int offset = getOffset(action.row, action.col);
            insertText(offset, action.text);
            int[] pos = calculateNewPosition(action.row, action.col, action.text);
            cursor[0] = pos[0];
            cursor[1] = pos[1];
        } else {
            int offset = getOffset(action.row, action.col);
            deleteText(offset, action.text.length());
            cursor[0] = action.row;
            cursor[1] = action.col;
        }
        return "Redone.";
    }

    // ------------------------------------------------------------------
    // File IO
    // ------------------------------------------------------------------

    public synchronized String saveToFile(String filename) {
        if (filename == null || filename.trim().isEmpty()) return "Error: Filename cannot be empty!";
        String name = filename.trim();
        try {
            Path path = Paths.get(name);

            // Stream the pieces straight to the file. The old version first built the whole document as
            // one String (two full copies, appended char by char) and then wrote that.
            try (BufferedWriter writer = new BufferedWriter(
                    new OutputStreamWriter(Files.newOutputStream(path), StandardCharsets.UTF_8.newEncoder()),
                    1 << 16)) {
                for (Piece p : pieces) {
                    if (p.source == BufferType.ORIGINAL) {
                        writer.write(originalBuffer, p.start, p.length);
                    } else {
                        writer.write(addBuffer.substring(p.start, p.start + p.length));
                    }
                }
                // POSIX-style: a non-empty file ends with exactly one trailing newline (same as vim).
                if (docLength > 0 && charAt(docLength - 1) != '\n') {
                    writer.write('\n');
                }
            }
            dirty = false;
            return "Saved to '" + name + "' successfully.";
        } catch (IOException | InvalidPathException e) {
            return "Error saving file: " + e.getMessage();
        }
    }

    public synchronized String loadFromFile(String filename) {
        if (filename == null || filename.trim().isEmpty()) return "Error: Filename cannot be empty!";
        String name = filename.trim();
        Path path;
        try {
            path = Paths.get(name);
        } catch (InvalidPathException e) {
            return "Error loading file: " + e.getMessage();
        }
        if (!Files.exists(path)) return "File '" + name + "' not found. New file will be created on save.";
        if (Files.isDirectory(path)) return "Error: '" + name + "' is a directory.";

        try {
            byte[] bytes = Files.readAllBytes(path);
            String content = new String(bytes, StandardCharsets.UTF_8);

            if (content.startsWith("﻿")) {
                content = content.substring(1);
            }

            loadInitialContent(content);
            undoStack.clear();
            redoStack.clear();
            dirty = false;
            return "Loaded '" + name + "' successfully.";
        } catch (Exception e) {
            return "Error loading file: " + e.getMessage();
        }
    }
}