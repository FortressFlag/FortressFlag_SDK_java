package com.fortressflag.server.json;

import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The bounded JSON parser (CLAUDE.md §3; ADR-0020 — the maintainer chose owning this surface
 * over the first runtime dependency in any FortressFlag SDK).
 *
 * <p>Recursive descent over input the transport already capped at 1 MiB. The bounds are
 * design, not hardening: nesting depth capped at {@link #MAX_DEPTH} (real payloads nest
 * 6); numbers gated by a strict JSON grammar BEFORE {@code Double.parseDouble}, which
 * alone accepts {@code NaN}, {@code Infinity}, hex floats and trailing type suffixes —
 * all of which every sibling SDK rejects, and accepting them here would be contract drift
 * in the newest port; duplicate object keys take the LAST occurrence (pinned by test —
 * matching encoding/json, JSON.parse and json.loads); UTF-8 is decoded with malformed
 * input REPORTED, never replaced; trailing non-whitespace after the top-level value
 * rejects. No reflection, no data binding, no streaming — it parses the contract's
 * payloads and the vectors, nothing more.
 */
public final class JsonParser {

    /** Real payloads nest 6; 64 refuses hostile deep nesting without ever biting a real one. */
    static final int MAX_DEPTH = 64;

    private static final Pattern JSON_NUMBER =
            Pattern.compile("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?");

    private final String input;
    private int position;

    private JsonParser(String input) {
        this.input = input;
    }

    /**
     * Parses one JSON document from UTF-8 bytes.
     *
     * @throws JsonParseException on any deviation — malformed UTF-8, bad grammar, depth,
     *     trailing garbage. Internal: the verifier maps it to a rejection code.
     */
    public static JsonValue parse(byte[] utf8) {
        String text;
        try {
            text = StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(utf8))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw new JsonParseException("malformed UTF-8", 0);
        }
        JsonParser parser = new JsonParser(text);
        parser.skipWhitespace();
        JsonValue value = parser.parseValue(0);
        parser.skipWhitespace();
        if (parser.position != text.length()) {
            throw new JsonParseException("trailing content after the top-level value", parser.position);
        }
        return value;
    }

    private JsonValue parseValue(int depth) {
        if (depth >= MAX_DEPTH) {
            throw new JsonParseException("nesting deeper than " + MAX_DEPTH, position);
        }
        if (position >= input.length()) {
            throw new JsonParseException("unexpected end of input", position);
        }
        char c = input.charAt(position);
        switch (c) {
            case '{':
                return parseObject(depth);
            case '[':
                return parseArray(depth);
            case '"':
                return new JsonValue.JsonString(parseString());
            case 't':
                expectLiteral("true");
                return new JsonValue.JsonBool(true);
            case 'f':
                expectLiteral("false");
                return new JsonValue.JsonBool(false);
            case 'n':
                expectLiteral("null");
                return JsonValue.JsonNull.INSTANCE;
            default:
                return parseNumber();
        }
    }

    private JsonValue parseObject(int depth) {
        position++; // '{'
        // LinkedHashMap + plain put: DUPLICATE KEYS TAKE THE LAST OCCURRENCE, matching
        // every sibling SDK's parser — putIfAbsent would silently take the FIRST and a
        // crafted payload would evaluate differently here than everywhere else.
        Map<String, JsonValue> members = new LinkedHashMap<>();
        skipWhitespace();
        if (peek() == '}') {
            position++;
            return new JsonValue.JsonObject(members);
        }
        while (true) {
            skipWhitespace();
            if (peek() != '"') {
                throw new JsonParseException("expected a member name", position);
            }
            String name = parseString();
            skipWhitespace();
            if (peek() != ':') {
                throw new JsonParseException("expected ':'", position);
            }
            position++;
            skipWhitespace();
            members.put(name, parseValue(depth + 1));
            skipWhitespace();
            char c = peek();
            if (c == ',') {
                position++;
                continue;
            }
            if (c == '}') {
                position++;
                return new JsonValue.JsonObject(members);
            }
            throw new JsonParseException("expected ',' or '}'", position);
        }
    }

    private JsonValue parseArray(int depth) {
        position++; // '['
        List<JsonValue> items = new ArrayList<>();
        skipWhitespace();
        if (peek() == ']') {
            position++;
            return new JsonValue.JsonArray(items);
        }
        while (true) {
            skipWhitespace();
            items.add(parseValue(depth + 1));
            skipWhitespace();
            char c = peek();
            if (c == ',') {
                position++;
                continue;
            }
            if (c == ']') {
                position++;
                return new JsonValue.JsonArray(items);
            }
            throw new JsonParseException("expected ',' or ']'", position);
        }
    }

    private String parseString() {
        position++; // opening quote
        StringBuilder builder = new StringBuilder();
        while (true) {
            if (position >= input.length()) {
                throw new JsonParseException("unterminated string", position);
            }
            char c = input.charAt(position);
            if (c == '"') {
                position++;
                return builder.toString();
            }
            if (c == '\\') {
                position++;
                builder.append(parseEscape());
                continue;
            }
            if (c < 0x20) {
                throw new JsonParseException("unescaped control character", position);
            }
            builder.append(c);
            position++;
        }
    }

    private char parseEscape() {
        if (position >= input.length()) {
            throw new JsonParseException("unterminated escape", position);
        }
        char c = input.charAt(position);
        position++;
        switch (c) {
            case '"':
                return '"';
            case '\\':
                return '\\';
            case '/':
                return '/';
            case 'b':
                return '\b';
            case 'f':
                return '\f';
            case 'n':
                return '\n';
            case 'r':
                return '\r';
            case 't':
                return '\t';
            case 'u':
                return parseUnicodeEscape();
            default:
                throw new JsonParseException("unknown escape '\\" + c + "'", position - 1);
        }
    }

    private char parseUnicodeEscape() {
        if (position + 4 > input.length()) {
            throw new JsonParseException("truncated \\u escape", position);
        }
        String hex = input.substring(position, position + 4);
        int code;
        try {
            code = Integer.parseInt(hex, 16);
        } catch (NumberFormatException exception) {
            throw new JsonParseException("bad \\u escape", position);
        }
        position += 4;
        // Surrogate pairs pass through as-is: Java strings are UTF-16, so a valid pair of
        // \\u escapes concatenates into the right code point, and a LONE surrogate stays a
        // lone surrogate exactly as JSON.parse and json.loads keep it — no re-validation.
        return (char) code;
    }

    private JsonValue parseNumber() {
        int start = position;
        while (position < input.length() && isNumberChar(input.charAt(position))) {
            position++;
        }
        String text = input.substring(start, position);
        // The strict grammar FIRST: parseDouble alone accepts NaN, Infinity, hex floats
        // (0x1p3), leading '+', and trailing 'd'/'f' — none of which is JSON, all of which
        // the sibling SDKs reject.
        if (!JSON_NUMBER.matcher(text).matches()) {
            throw new JsonParseException("not a JSON number", start);
        }
        double value = Double.parseDouble(text);
        if (!Double.isFinite(value)) {
            // 1e999 overflows to Infinity though the grammar allowed it; a non-finite
            // number cannot round-trip JSON and is refused.
            throw new JsonParseException("number overflows a double", start);
        }
        return new JsonValue.JsonNumber(value);
    }

    private static boolean isNumberChar(char c) {
        return (c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E'
                || c == 'x' || c == 'X' || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    private char peek() {
        if (position >= input.length()) {
            throw new JsonParseException("unexpected end of input", position);
        }
        return input.charAt(position);
    }

    private void expectLiteral(String literal) {
        if (!input.startsWith(literal, position)) {
            throw new JsonParseException("expected '" + literal + "'", position);
        }
        position += literal.length();
    }

    private void skipWhitespace() {
        while (position < input.length()) {
            char c = input.charAt(position);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                position++;
            } else {
                return;
            }
        }
    }
}
