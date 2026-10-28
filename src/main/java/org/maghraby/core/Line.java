package org.maghraby.core;

/** One screen line under construction: ANSI-coloured text plus its visible width. */
public final class Line {
    public final StringBuilder colored = new StringBuilder();
    public int len = 0;

    public Line add(String style, String text) {
        colored.append(style).append(text);
        len += text.length();
        return this;
    }
}
