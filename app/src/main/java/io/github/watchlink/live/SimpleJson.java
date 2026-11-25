package io.github.watchlink.live;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal pure-Java RFC 8259 JSON parser & escaper (zero Android / third-party deps)
 * so live-window codecs and Fluid Cloud parsers run identically on Android ART and JVM unit tests.
 */
final class SimpleJson {
    private static final int MAX_LEN = 65_536;
    private static final int MAX_DEPTH = 16;

    private final String src;
    private final int len;
    private int pos;

    private SimpleJson(String src) {
        this.src = src;
        this.len = src.length();
    }

    static Object parse(String raw) {
        if (raw == null || raw.isEmpty() || raw.length() > MAX_LEN) return null;
        SimpleJson parser = new SimpleJson(raw);
        parser.skipWs();
        if (parser.pos >= parser.len) return null;
        Object value = parser.parseValue(0);
        if (value == null) return null;
        parser.skipWs();
        return parser.pos == parser.len ? value : null;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> parseObject(String raw) {
        Object parsed = parse(raw);
        return parsed instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }

    private Object parseValue(int depth) {
        if (depth > MAX_DEPTH || pos >= len) return null;
        char c = src.charAt(pos);
        return switch (c) {
            case '{' -> parseObjectNode(depth + 1);
            case '[' -> parseArrayNode(depth + 1);
            case '"' -> parseString();
            case 't' -> matchLiteral("true", Boolean.TRUE);
            case 'f' -> matchLiteral("false", Boolean.FALSE);
            case 'n' -> matchLiteral("null", NullToken.INSTANCE);
            default -> (c == '-' || (c >= '0' && c <= '9')) ? parseNumber() : null;
        };
    }

    private Map<String, Object> parseObjectNode(int depth) {
        pos++; // '{'
        skipWs();
        Map<String, Object> out = new LinkedHashMap<>();
        if (pos < len && src.charAt(pos) == '}') {
            pos++;
            return out;
        }
        while (pos < len) {
            skipWs();
            if (pos >= len || src.charAt(pos) != '"') return null;
            String key = parseString();
            if (key == null) return null;
            skipWs();
            if (pos >= len || src.charAt(pos) != ':') return null;
            pos++;
            skipWs();
            Object val = parseValue(depth);
            if (val == null) return null;
            out.put(key, val == NullToken.INSTANCE ? null : val);
            skipWs();
            if (pos >= len) return null;
            char next = src.charAt(pos++);
            if (next == '}') return out;
            if (next != ',') return null;
        }
        return null;
    }

    private List<Object> parseArrayNode(int depth) {
        pos++; // '['
        skipWs();
        List<Object> out = new ArrayList<>();
        if (pos < len && src.charAt(pos) == ']') {
            pos++;
            return out;
        }
        while (pos < len) {
            skipWs();
            Object val = parseValue(depth);
            if (val == null) return null;
            out.add(val == NullToken.INSTANCE ? null : val);
            skipWs();
            if (pos >= len) return null;
            char next = src.charAt(pos++);
            if (next == ']') return out;
            if (next != ',') return null;
        }
        return null;
    }

    private String parseString() {
        pos++; // opening '"'
        StringBuilder sb = new StringBuilder();
        while (pos < len) {
            char c = src.charAt(pos++);
            if (c == '"') return sb.toString();
            if (c == '\\') {
                if (pos >= len) return null;
                char esc = src.charAt(pos++);
                switch (esc) {
                    case '"', '\\', '/' -> sb.append(esc);
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        if (pos + 4 > len) return null;
                        int code = 0;
                        for (int i = 0; i < 4; i++) {
                            int digit = Character.digit(src.charAt(pos++), 16);
                            if (digit < 0) return null;
                            code = (code << 4) | digit;
                        }
                        sb.append((char) code);
                    }
                    default -> { return null; }
                }
            } else if (c < 0x20) {
                return null;
            } else {
                sb.append(c);
            }
        }
        return null;
    }

    private Number parseNumber() {
        int start = pos;
        if (pos < len && src.charAt(pos) == '-') pos++;
        if (pos >= len) return null;
        if (src.charAt(pos) == '0') {
            pos++;
        } else if (src.charAt(pos) >= '1' && src.charAt(pos) <= '9') {
            while (pos < len && src.charAt(pos) >= '0' && src.charAt(pos) <= '9') pos++;
        } else {
            return null;
        }
        boolean floating = false;
        if (pos < len && src.charAt(pos) == '.') {
            floating = true;
            pos++;
            int fracStart = pos;
            while (pos < len && src.charAt(pos) >= '0' && src.charAt(pos) <= '9') pos++;
            if (pos == fracStart) return null;
        }
        if (pos < len && (src.charAt(pos) == 'e' || src.charAt(pos) == 'E')) {
            floating = true;
            pos++;
            if (pos < len && (src.charAt(pos) == '+' || src.charAt(pos) == '-')) pos++;
            int expStart = pos;
            while (pos < len && src.charAt(pos) >= '0' && src.charAt(pos) <= '9') pos++;
            if (pos == expStart) return null;
        }
        String token = src.substring(start, pos);
        try {
            if (floating) return Double.valueOf(token);
            return Long.valueOf(token);
        } catch (NumberFormatException ignored) {
            try { return Double.valueOf(token); }
            catch (NumberFormatException bad) { return null; }
        }
    }

    private Object matchLiteral(String literal, Object value) {
        if (src.startsWith(literal, pos)) {
            pos += literal.length();
            return value;
        }
        return null;
    }

    private void skipWs() {
        while (pos < len) {
            char c = src.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n') pos++;
            else break;
        }
    }

    static void appendQuoted(StringBuilder sb, String value) {
        sb.append('"');
        if (value != null) {
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                switch (c) {
                    case '"' -> sb.append("\\\"");
                    case '\\' -> sb.append("\\\\");
                    case '\b' -> sb.append("\\b");
                    case '\f' -> sb.append("\\f");
                    case '\n' -> sb.append("\\n");
                    case '\r' -> sb.append("\\r");
                    case '\t' -> sb.append("\\t");
                    default -> {
                        if (c < 0x20) {
                            sb.append(String.format("\\u%04x", (int) c));
                        } else {
                            sb.append(c);
                        }
                    }
                }
            }
        }
        sb.append('"');
    }

    private enum NullToken { INSTANCE }
}
