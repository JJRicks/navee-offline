package com.jjricks.volt;

import java.io.IOException;
import java.util.*;

/** Small, strict, bounded JSON reader for backup planning without Android's org.json. Objects
 * keep key order; integers are Long, other numbers Double, null is {@link #NULL}. Top-level array
 * elements also keep their exact source text so profile lists can be spliced without rewriting. */
final class BackupJson {
    static final Object NULL = new Object() { @Override public String toString() { return "null"; } };
    private final String text;
    private final int maxDepth;
    private int at;
    private final List<String> rawElements = new ArrayList<>();
    private BackupJson(String text, int maxDepth) { this.text = text; this.maxDepth = maxDepth; }

    static Object parse(String text, int maxDepth) throws IOException { return new BackupJson(text, maxDepth).document(); }
    /** Exact source text of each element of a top-level array. */
    static List<String> elements(String text, int maxDepth) throws IOException {
        BackupJson parser = new BackupJson(text, maxDepth);
        if (!(parser.document() instanceof List)) throw new IOException("Expected a JSON list.");
        return parser.rawElements;
    }
    @SuppressWarnings("unchecked")
    static Map<String, Object> object(Object value) throws IOException {
        if (!(value instanceof Map)) throw new IOException("Expected a JSON object.");
        return (Map<String, Object>) value;
    }
    static String quote(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"' || c == '\\') out.append('\\').append(c);
            else if (c < 0x20) out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
            else out.append(c);
        }
        return out.append('"').toString();
    }

    private Object document() throws IOException {
        if (text == null) throw new IOException("Missing JSON.");
        Object value = value(0, true);
        space();
        if (at != text.length()) throw new IOException("Unexpected JSON content.");
        return value;
    }
    private Object value(int depth, boolean top) throws IOException {
        space();
        if (at >= text.length()) throw new IOException("Incomplete JSON.");
        char c = text.charAt(at);
        if (c == '{' || c == '[') {
            if (depth >= maxDepth) throw new IOException("JSON is nested too deeply.");
            at++;
            if (c == '{') {
                Map<String, Object> result = new LinkedHashMap<>();
                space();
                if (peek('}')) return result;
                do {
                    space();
                    if (!peek('"')) throw new IOException("Invalid JSON key.");
                    at--;
                    String key = string();
                    space();
                    if (!peek(':')) throw new IOException("Invalid JSON object.");
                    if (result.put(key, value(depth + 1, false)) != null) throw new IOException("Duplicate JSON key.");
                    space();
                } while (peek(','));
                if (!peek('}')) throw new IOException("Invalid JSON object.");
                return result;
            }
            List<Object> result = new ArrayList<>();
            space();
            if (peek(']')) return result;
            do {
                space();
                int start = at;
                result.add(value(depth + 1, false));
                if (top) rawElements.add(text.substring(start, at));
                space();
            } while (peek(','));
            if (!peek(']')) throw new IOException("Invalid JSON list.");
            return result;
        }
        if (c == '"') return string();
        if (text.startsWith("true", at)) { at += 4; return Boolean.TRUE; }
        if (text.startsWith("false", at)) { at += 5; return Boolean.FALSE; }
        if (text.startsWith("null", at)) { at += 4; return NULL; }
        return number();
    }
    private boolean peek(char c) { if (at < text.length() && text.charAt(at) == c) { at++; return true; } return false; }
    private void space() { while (at < text.length() && " \t\r\n".indexOf(text.charAt(at)) >= 0) at++; }
    private String string() throws IOException {
        at++;
        StringBuilder out = new StringBuilder();
        while (true) {
            if (at >= text.length()) throw new IOException("Unterminated JSON text.");
            char c = text.charAt(at++);
            if (c == '"') return out.toString();
            if (c < 0x20) throw new IOException("Invalid JSON text.");
            if (c != '\\') { out.append(c); continue; }
            if (at >= text.length()) throw new IOException("Invalid JSON escape.");
            char e = text.charAt(at++);
            int i = "\"\\/bfnrt".indexOf(e);
            if (i >= 0) out.append("\"\\/\b\f\n\r\t".charAt(i));
            else if (e == 'u' && at + 4 <= text.length()) {
                try { out.append((char) Integer.parseInt(text.substring(at, at + 4), 16)); }
                catch (NumberFormatException bad) { throw new IOException("Invalid JSON escape."); }
                at += 4;
            } else throw new IOException("Invalid JSON escape.");
        }
    }
    private Object number() throws IOException {
        int start = at;
        peek('-');
        int digits = at;
        while (at < text.length() && text.charAt(at) >= '0' && text.charAt(at) <= '9') at++;
        if (at == digits || (text.charAt(digits) == '0' && at - digits > 1)) throw new IOException("Invalid JSON value.");
        boolean integral = true;
        if (peek('.')) { integral = false; int fraction = at; while (at < text.length() && text.charAt(at) >= '0' && text.charAt(at) <= '9') at++; if (at == fraction) throw new IOException("Invalid JSON number."); }
        if (at < text.length() && (text.charAt(at) == 'e' || text.charAt(at) == 'E')) {
            integral = false; at++;
            if (!peek('+')) peek('-');
            int exponent = at; while (at < text.length() && text.charAt(at) >= '0' && text.charAt(at) <= '9') at++;
            if (at == exponent) throw new IOException("Invalid JSON number.");
        }
        String value = text.substring(start, at);
        if (value.length() > 64) throw new IOException("Invalid JSON number.");
        if (integral) try { return Long.parseLong(value); } catch (NumberFormatException large) { integral = false; }
        double parsed = Double.parseDouble(value);
        if (!Double.isFinite(parsed)) throw new IOException("Invalid JSON number.");
        return parsed;
    }
}
