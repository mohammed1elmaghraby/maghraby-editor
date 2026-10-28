package org.maghraby.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tiny JSON reader/writer so the editor stays zero-dependency.
 *
 * Parsed values are: Map&lt;String,Object&gt;, List&lt;Object&gt;, String, Long, Double, Boolean or null.
 * Writing accepts the same types (plus any Number).
 */
public final class Json {
    private Json() {}

    // ------------------------------------------------------------------
    // Builders / safe accessors
    // ------------------------------------------------------------------

    /** obj("a", 1, "b", "x") -> {"a":1,"b":"x"} (insertion ordered). */
    public static Map<String, Object> obj(Object... keyValues) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) m.put((String) keyValues[i], keyValues[i + 1]);
        return m;
    }

    public static List<Object> list(Object... items) {
        List<Object> l = new ArrayList<>(items.length);
        for (Object o : items) l.add(o);
        return l;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> asList(Object o) {
        return o instanceof List ? (List<Object>) o : null;
    }

    public static String asString(Object o) {
        return o instanceof String ? (String) o : null;
    }

    public static int asInt(Object o, int fallback) {
        return o instanceof Number ? ((Number) o).intValue() : fallback;
    }

    // ------------------------------------------------------------------
    // Writer
    // ------------------------------------------------------------------

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder(128);
        write(sb, value);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object v) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String) {
            writeString(sb, (String) v);
        } else if (v instanceof Boolean) {
            sb.append(((Boolean) v) ? "true" : "false");
        } else if (v instanceof Double || v instanceof Float) {
            double d = ((Number) v).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) sb.append("null");
            else sb.append(d);
        } else if (v instanceof Number) {
            sb.append(((Number) v).longValue());
        } else if (v instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                if (!first) sb.append(',');
                first = false;
                writeString(sb, String.valueOf(e.getKey()));
                sb.append(':');
                write(sb, e.getValue());
            }
            sb.append('}');
        } else if (v instanceof List) {
            sb.append('[');
            boolean first = true;
            for (Object o : (List<?>) v) {
                if (!first) sb.append(',');
                first = false;
                write(sb, o);
            }
            sb.append(']');
        } else {
            writeString(sb, v.toString());
        }
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c); // non-ASCII is fine: the transport encodes UTF-8
            }
        }
        sb.append('"');
    }

    // ------------------------------------------------------------------
    // Parser
    // ------------------------------------------------------------------

    public static Object parse(String text) {
        Parser p = new Parser(text);
        p.skipWs();
        Object v = p.value();
        p.skipWs();
        if (p.pos != text.length()) throw p.error("Trailing characters");
        return v;
    }

    private static final class Parser {
        final String s;
        int pos = 0;

        Parser(String s) { this.s = s; }

        IllegalArgumentException error(String msg) {
            return new IllegalArgumentException("JSON: " + msg + " at " + pos);
        }

        void skipWs() {
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') pos++;
                else break;
            }
        }

        Object value() {
            if (pos >= s.length()) throw error("Unexpected end");
            char c = s.charAt(pos);
            switch (c) {
                case '{': return object();
                case '[': return array();
                case '"': return string();
                case 't': expect("true"); return Boolean.TRUE;
                case 'f': expect("false"); return Boolean.FALSE;
                case 'n': expect("null"); return null;
                default: return number();
            }
        }

        void expect(String word) {
            if (!s.startsWith(word, pos)) throw error("Expected " + word);
            pos += word.length();
        }

        Map<String, Object> object() {
            Map<String, Object> m = new LinkedHashMap<>();
            pos++; // {
            skipWs();
            if (pos < s.length() && s.charAt(pos) == '}') { pos++; return m; }
            while (true) {
                skipWs();
                if (pos >= s.length() || s.charAt(pos) != '"') throw error("Expected key");
                String key = string();
                skipWs();
                if (pos >= s.length() || s.charAt(pos) != ':') throw error("Expected ':'");
                pos++;
                skipWs();
                m.put(key, value());
                skipWs();
                if (pos >= s.length()) throw error("Unterminated object");
                char c = s.charAt(pos++);
                if (c == '}') return m;
                if (c != ',') throw error("Expected ',' or '}'");
            }
        }

        List<Object> array() {
            List<Object> l = new ArrayList<>();
            pos++; // [
            skipWs();
            if (pos < s.length() && s.charAt(pos) == ']') { pos++; return l; }
            while (true) {
                skipWs();
                l.add(value());
                skipWs();
                if (pos >= s.length()) throw error("Unterminated array");
                char c = s.charAt(pos++);
                if (c == ']') return l;
                if (c != ',') throw error("Expected ',' or ']'");
            }
        }

        String string() {
            pos++; // opening quote
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (pos >= s.length()) throw error("Unterminated string");
                char c = s.charAt(pos++);
                if (c == '"') return sb.toString();
                if (c != '\\') { sb.append(c); continue; }
                if (pos >= s.length()) throw error("Bad escape");
                char e = s.charAt(pos++);
                switch (e) {
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'u':
                        if (pos + 4 > s.length()) throw error("Bad \\u escape");
                        sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16)); // surrogate pairs pair up naturally
                        pos += 4;
                        break;
                    default: throw error("Bad escape \\" + e);
                }
            }
        }

        Object number() {
            int start = pos;
            boolean fractional = false;
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == '.' || c == 'e' || c == 'E') { fractional = true; pos++; }
                else if ((c >= '0' && c <= '9') || c == '-' || c == '+') pos++;
                else break;
            }
            if (start == pos) throw error("Unexpected character '" + s.charAt(pos) + "'");
            String t = s.substring(start, pos);
            try {
                return fractional ? (Object) Double.valueOf(t) : (Object) Long.valueOf(t);
            } catch (NumberFormatException ex) {
                throw error("Bad number " + t);
            }
        }
    }
}
