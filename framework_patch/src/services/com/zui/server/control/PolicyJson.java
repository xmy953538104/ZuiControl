package com.zui.server.control;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Bounded strict JSON at the policy/import trust boundary. No Android dependency. */
final class PolicyJson {
    private final String text;
    private int at;
    private PolicyJson(String value) { text = value; }
    static void require(boolean value, String reason) {
        if (!value) throw new IllegalArgumentException(reason);
    }
    static Object parse(byte[] bytes) throws Exception {
        require(bytes.length > 0 && bytes.length <= 262144, "JSON size");
        String text = utf8(bytes);
        PolicyJson reader = new PolicyJson(text);
        Object value = reader.value(0); reader.space();
        require(reader.at == text.length(), "JSON trailing data");
        return value;
    }
    static String utf8(byte[] bytes) throws java.nio.charset.CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }
    private void space() { while (at < text.length() && " \t\r\n".indexOf(text.charAt(at)) >= 0) at++; }
    private char take() { require(at < text.length(), "JSON truncated"); return text.charAt(at++); }
    private Object value(int depth) {
        require(depth <= 24, "JSON depth"); space(); char c = take();
        if (c == '"') return string();
        if (c == '{') {
            Map<String,Object> result = new TreeMap<>(); space();
            if (at < text.length() && text.charAt(at) == '}') { at++; return result; }
            for (;;) {
                space(); require(take() == '"', "JSON object key"); String key = string(); space();
                require(take() == ':' && !result.containsKey(key), "JSON duplicate key/separator");
                result.put(key, value(depth + 1)); require(result.size() <= 4096, "JSON object bound");
                space(); char end = take(); if (end == '}') return result;
                require(end == ',', "JSON object delimiter");
            }
        }
        if (c == '[') {
            List<Object> result = new ArrayList<>(); space();
            if (at < text.length() && text.charAt(at) == ']') { at++; return result; }
            for (;;) {
                result.add(value(depth + 1)); require(result.size() <= 4096, "JSON array bound");
                space(); char end = take(); if (end == ']') return result;
                require(end == ',', "JSON array delimiter");
            }
        }
        int start = --at;
        while (at < text.length() && " \t\r\n,]}".indexOf(text.charAt(at)) < 0) at++;
        String word = text.substring(start, at);
        if (word.equals("true")) return Boolean.TRUE;
        if (word.equals("false")) return Boolean.FALSE;
        if (word.equals("null")) return null;
        require(word.length() <= 64 && word.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?"), "JSON number");
        BigDecimal number = new BigDecimal(word);
        require(Math.abs((long) number.scale()) <= 64 && number.precision() <= 64, "JSON numeric bound");
        return number;
    }
    private String string() {
        StringBuilder out = new StringBuilder();
        for (;;) {
            char c = take(); if (c == '"') break;
            require(c >= 32, "JSON string control");
            if (c == '\\') {
                c = take(); int i = "\"\\/bfnrt".indexOf(c);
                if (c == 'u') {
                    require(at + 4 <= text.length(), "JSON unicode");
                    String hex = text.substring(at, at + 4); require(hex.matches("[0-9a-fA-F]{4}"), "JSON unicode");
                    c = (char) Integer.parseInt(hex, 16); at += 4;
                } else { require(i >= 0, "JSON escape"); c = "\"\\/\b\f\n\r\t".charAt(i); }
            }
            out.append(c); require(out.length() <= 131072, "JSON string bound");
        }
        for (int i = 0; i < out.length(); i++) {
            char c = out.charAt(i);
            if (Character.isHighSurrogate(c)) require(++i < out.length() && Character.isLowSurrogate(out.charAt(i)), "JSON surrogate");
            else require(!Character.isLowSurrogate(c), "JSON surrogate");
        }
        return out.toString();
    }
    @SuppressWarnings("unchecked") static Map<String,Object> object(Object value) {
        require(value instanceof Map, "JSON object required"); return (Map<String,Object>) value;
    }
    @SuppressWarnings("unchecked") static List<Object> array(Object value) {
        require(value instanceof List, "JSON array required"); return (List<Object>) value;
    }
    static String string(Object value) { require(value instanceof String, "JSON string required"); return (String) value; }
    static long integer(Object value) {
        require(value instanceof Number, "JSON integer required");
        return new BigDecimal(value.toString()).longValueExact();
    }
    static int intValue(Object value) { return Math.toIntExact(integer(value)); }
    static void keys(Map<String,Object> value, String... names) {
        require(value.keySet().equals(new java.util.TreeSet<>(Arrays.asList(names))), "JSON field set");
    }
    static Map<String,Object> map(Object... fields) {
        Map<String,Object> result = new TreeMap<>();
        for (int i = 0; i < fields.length; i += 2) result.put((String) fields[i], fields[i + 1]);
        return result;
    }
    static byte[] bytes(Object value) { return (encode(value) + "\n").getBytes(StandardCharsets.UTF_8); }
    static String encode(Object value) {
        if (value == null) return "null";
        if (value instanceof Map) {
            StringBuilder out = new StringBuilder("{"); boolean comma = false;
            for (Map.Entry<String,Object> e : new TreeMap<>(object(value)).entrySet()) {
                if (comma) out.append(','); comma = true;
                out.append(encode(e.getKey())).append(':').append(encode(e.getValue()));
            }
            return out.append('}').toString();
        }
        if (value instanceof List) {
            StringBuilder out = new StringBuilder("["); boolean comma = false;
            for (Object v : array(value)) { if (comma) out.append(','); comma = true; out.append(encode(v)); }
            return out.append(']').toString();
        }
        if (value instanceof Boolean) return value.toString();
        if (value instanceof Number) return new BigDecimal(value.toString()).stripTrailingZeros().toPlainString();
        String text = string(value); StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"' || c == '\\') out.append('\\').append(c);
            else if (c < 32) out.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) c));
            else out.append(c);
        }
        return out.append('"').toString();
    }
    static String hash(byte[] bytes) {
        try {
            StringBuilder result = new StringBuilder();
            for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) result.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            return result.toString();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
