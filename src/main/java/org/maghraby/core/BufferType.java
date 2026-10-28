package org.maghraby.core;

enum BufferType { ORIGINAL, ADD }

class Piece {
    BufferType source; // Indicates if the text is from the original file or newly added by the user
    int start;         // The starting index of the text inside the target buffer
    int length;        // The number of characters contained in this piece
}