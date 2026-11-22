package io.primelogiclab.mailroom.http;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.exc.StreamConstraintsException;
import com.fasterxml.jackson.core.io.JsonEOFException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * A parsed JSON value, read tolerantly: navigating never throws, so a provider reads the fields it knows and ignores
 * the rest. Immutable.
 *
 * <pre>{@code
 * JsonValue json = response.json();
 * Optional<String> messageId = json.get("data").get("email_id").asString();
 * long failed = json.get("data").get("failed").asLong().orElse(0);
 * }</pre>
 *
 * <p>{@link #get(String)} and {@link #get(int)} return a <em>missing</em> value when the field or element isn't there,
 * or the value isn't an object or array, and every accessor on a missing value is empty; so a chain like the one above
 * is simply empty when any step is absent. Accessors convert where the meaning is plain: {@link #asString()} gives the
 * text of a number or boolean too, and {@link #asLong()} reads {@code "12"} as well as {@code 12}.
 *
 * <p>Parsing itself is strict: the input must be exactly one JSON value. Error messages give the line and column but
 * never quote the input, which may hold addresses or credentials. {@link #toString()} shows only the type and size; use
 * {@link #toJson()} for the content.
 *
 * <p>Equal values are equal regardless of the order of object fields, and numbers are compared by value, so
 * {@code 1} equals {@code 1.0}.
 *
 * @since 0.1.0
 */
public final class JsonValue {

    /**
     * The kind of a {@link JsonValue}.
     *
     * @since 0.1.0
     */
    public enum Type {

        /**
         * A JSON object.
         *
         * @since 0.1.0
         */
        OBJECT,

        /**
         * A JSON array.
         *
         * @since 0.1.0
         */
        ARRAY,

        /**
         * A JSON string.
         *
         * @since 0.1.0
         */
        STRING,

        /**
         * A JSON number.
         *
         * @since 0.1.0
         */
        NUMBER,

        /**
         * {@code true} or {@code false}.
         *
         * @since 0.1.0
         */
        BOOLEAN,

        /**
         * JSON {@code null}.
         *
         * @since 0.1.0
         */
        NULL,

        /**
         * Not there: what {@link JsonValue#get(String)} and {@link JsonValue#get(int)} return for an absent field or
         * element. Never the result of parsing.
         *
         * @since 0.1.0
         */
        MISSING
    }

    private static final JsonValue MISSING = new JsonValue(Type.MISSING, null);
    private static final JsonValue NULL = new JsonValue(Type.NULL, null);
    private static final JsonValue TRUE = new JsonValue(Type.BOOLEAN, Boolean.TRUE);
    private static final JsonValue FALSE = new JsonValue(Type.BOOLEAN, Boolean.FALSE);

    /** The longest string {@link #asLong()} and {@link #asDecimal()} convert; Jackson's own limit for numbers. */
    private static final int MAX_NUMBER_TEXT = 1000;

    private final Type type;
    /** Map of fields, List of elements, String for strings and number text, Boolean; null for NULL and MISSING. */
    private final Object value;

    private JsonValue(Type type, Object value) {
        this.type = type;
        this.value = value;
    }

    /**
     * Parses JSON text.
     *
     * @param json the text: exactly one JSON value, with only whitespace around it
     * @return the value
     * @throws IllegalArgumentException if the text is empty, isn't valid JSON, holds more than one value or nests
     *                                  deeper than 100 levels; the message gives the line and column, not the text
     * @since 0.1.0
     */
    public static JsonValue parse(final String json) {
        Objects.requireNonNull(json, "json");
        return read(new Source() {
            @Override
            public JsonParser open() throws IOException {
                return JsonSupport.FACTORY.createParser(json);
            }
        }, "the input");
    }

    /**
     * Parses JSON bytes, in UTF-8 (UTF-16 and UTF-32 are recognised too, as is a byte order mark).
     *
     * @param json the bytes: exactly one JSON value, with only whitespace around it
     * @return the value
     * @throws IllegalArgumentException if the bytes are empty, aren't valid JSON, hold more than one value or nest
     *                                  deeper than 100 levels; the message gives the line and column, not the bytes
     * @since 0.1.0
     */
    public static JsonValue parse(byte[] json) {
        return parse(Objects.requireNonNull(json, "json"), "the input");
    }

    /** Parses bytes, naming them {@code subject} in error messages, such as "the 200 response body". */
    static JsonValue parse(final byte[] json, String subject) {
        return read(new Source() {
            @Override
            public JsonParser open() throws IOException {
                return JsonSupport.FACTORY.createParser(json);
            }
        }, subject);
    }

    private interface Source {
        JsonParser open() throws IOException;
    }

    private static JsonValue read(Source source, String subject) {
        try {
            JsonParser parser = source.open();
            try {
                return readDocument(parser, subject);
            } finally {
                parser.close();
            }
        } catch (IOException e) {
            throw malformed(subject, e);
        }
    }

    /**
     * Returns the kind of value.
     *
     * @return the type; {@link Type#MISSING} for an absent field or element
     * @since 0.1.0
     */
    public Type type() {
        return type;
    }

    /**
     * Tells whether this stands for an absent field or element.
     *
     * @return true for {@link Type#MISSING}
     * @since 0.1.0
     */
    public boolean isMissing() {
        return type == Type.MISSING;
    }

    /**
     * Tells whether this is JSON {@code null}. A missing value is not null.
     *
     * @return true for {@link Type#NULL}
     * @since 0.1.0
     */
    public boolean isNull() {
        return type == Type.NULL;
    }

    /**
     * Returns a field of an object.
     *
     * @param name the field name, exactly as in the JSON
     * @return the field's value; a missing value if this isn't an object or has no such field
     * @since 0.1.0
     */
    public JsonValue get(String name) {
        Objects.requireNonNull(name, "name");
        if (type != Type.OBJECT) {
            return MISSING;
        }
        JsonValue field = fieldMap().get(name);
        return field == null ? MISSING : field;
    }

    /**
     * Returns an element of an array.
     *
     * @param index the position, from 0
     * @return the element; a missing value if this isn't an array or the index is out of range
     * @since 0.1.0
     */
    public JsonValue get(int index) {
        if (type != Type.ARRAY || index < 0 || index >= elementList().size()) {
            return MISSING;
        }
        return elementList().get(index);
    }

    /**
     * Returns the fields of an object, in the order they appeared. Where a name appeared more than once, the last
     * value is kept.
     *
     * @return values by field name; empty if this isn't an object; the map cannot be changed
     * @since 0.1.0
     */
    public Map<String, JsonValue> fields() {
        return type == Type.OBJECT ? fieldMap() : Collections.<String, JsonValue>emptyMap();
    }

    /**
     * Returns the elements of an array.
     *
     * @return the elements in order; empty if this isn't an array; the list cannot be changed
     * @since 0.1.0
     */
    public List<JsonValue> elements() {
        return type == Type.ARRAY ? elementList() : Collections.<JsonValue>emptyList();
    }

    /**
     * Returns the number of fields of an object or elements of an array.
     *
     * @return the count; 0 for other values
     * @since 0.1.0
     */
    public int size() {
        return type == Type.OBJECT ? fieldMap().size() : type == Type.ARRAY ? elementList().size() : 0;
    }

    /**
     * Returns the value as text: a string as it is, and a number or boolean as its JSON text.
     *
     * @return the text; empty for objects, arrays, null and missing values
     * @since 0.1.0
     */
    public Optional<String> asString() {
        switch (type) {
            case STRING:
            case NUMBER:
                return Optional.of((String) value);
            case BOOLEAN:
                return Optional.of(value.toString());
            default:
                return Optional.empty();
        }
    }

    /**
     * Returns the value as a whole number: a number, or a string holding one, such as {@code 12}, {@code 12.0} or
     * {@code "12"}.
     *
     * @return the number; empty if the value isn't a number or numeric string, has a fraction, or doesn't fit a long
     * @since 0.1.0
     */
    public OptionalLong asLong() {
        BigDecimal decimal = decimalOrNull();
        if (decimal == null) {
            return OptionalLong.empty();
        }
        try {
            return OptionalLong.of(decimal.longValueExact());
        } catch (ArithmeticException e) {
            return OptionalLong.empty();
        }
    }

    /**
     * Returns the value as a decimal number: a number, or a string holding one.
     *
     * @return the number; empty if the value isn't a number or numeric string, or its exponent is out of range
     * @since 0.1.0
     */
    public Optional<BigDecimal> asDecimal() {
        return Optional.ofNullable(decimalOrNull());
    }

    /**
     * Returns the value as a boolean: {@code true} or {@code false}, or a string holding one in any letter case.
     *
     * @return the boolean; empty for other values
     * @since 0.1.0
     */
    public Optional<Boolean> asBoolean() {
        if (type == Type.BOOLEAN) {
            return Optional.of((Boolean) value);
        }
        if (type == Type.STRING) {
            String s = ((String) value).trim().toLowerCase(Locale.ROOT);
            if (s.equals("true")) {
                return Optional.of(Boolean.TRUE);
            }
            if (s.equals("false")) {
                return Optional.of(Boolean.FALSE);
            }
        }
        return Optional.empty();
    }

    /**
     * Returns the value as compact JSON text. It shows the content, which may hold addresses or credentials, so don't
     * log it; it is meant for tests and contract checks.
     *
     * @return the JSON text
     * @throws IllegalStateException if this is a missing value, which has no JSON form
     * @since 0.1.0
     */
    public String toJson() {
        if (type == Type.MISSING) {
            throw new IllegalStateException("a missing value has no JSON form");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            JsonGenerator generator = JsonSupport.FACTORY.createGenerator(out);
            writeTo(generator);
            generator.close();
        } catch (IOException e) {
            throw new UncheckedIOException("writing to memory failed", e);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    /**
     * Compares by content: object fields in any order, numbers by value.
     *
     * @param other the object to compare with
     * @return true if both are JSON values with the same content
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof JsonValue)) {
            return false;
        }
        JsonValue that = (JsonValue) other;
        if (type != that.type) {
            return false;
        }
        if (type == Type.NUMBER) {
            BigDecimal a = decimalOrNull();
            BigDecimal b = that.decimalOrNull();
            return a != null && b != null ? a.compareTo(b) == 0 : value.equals(that.value);
        }
        return Objects.equals(value, that.value);
    }

    /**
     * Returns a hash code consistent with {@link #equals(Object)}.
     *
     * @return the hash code
     */
    @Override
    public int hashCode() {
        if (type == Type.NUMBER) {
            BigDecimal decimal = decimalOrNull();
            return decimal != null ? decimal.stripTrailingZeros().hashCode() : value.hashCode();
        }
        return type.hashCode() * 31 + Objects.hashCode(value);
    }

    /**
     * Describes the value without its content, such as {@code JsonValue[object, 3 fields]}.
     *
     * @return a description
     */
    @Override
    public String toString() {
        switch (type) {
            case OBJECT:
                return "JsonValue[object, " + count(fieldMap().size(), "field") + "]";
            case ARRAY:
                return "JsonValue[array, " + count(elementList().size(), "element") + "]";
            case STRING:
                return "JsonValue[string, " + count(((String) value).length(), "char") + "]";
            default:
                return "JsonValue[" + type.name().toLowerCase(Locale.ROOT) + "]";
        }
    }

    /** Writes this value at the generator's current position; for {@link JsonWriter} and {@link #toJson()}. */
    void writeTo(JsonGenerator generator) throws IOException {
        switch (type) {
            case OBJECT:
                generator.writeStartObject();
                for (Map.Entry<String, JsonValue> field : fieldMap().entrySet()) {
                    generator.writeFieldName(checkText(field.getKey()));
                    field.getValue().writeTo(generator);
                }
                generator.writeEndObject();
                break;
            case ARRAY:
                generator.writeStartArray();
                for (JsonValue element : elementList()) {
                    element.writeTo(generator);
                }
                generator.writeEndArray();
                break;
            case STRING:
                generator.writeString(checkText((String) value));
                break;
            case NUMBER:
                generator.writeNumber((String) value);
                break;
            case BOOLEAN:
                generator.writeBoolean((Boolean) value);
                break;
            case NULL:
                generator.writeNull();
                break;
            default:
                throw new IllegalArgumentException("a missing JsonValue cannot be written");
        }
    }

    private static String checkText(String text) {
        if (JsonSupport.hasBrokenSurrogate(text)) {
            throw new IllegalArgumentException("a string has a broken UTF-16 surrogate pair");
        }
        return text;
    }

    @SuppressWarnings("unchecked")
    private Map<String, JsonValue> fieldMap() {
        return (Map<String, JsonValue>) value;
    }

    @SuppressWarnings("unchecked")
    private List<JsonValue> elementList() {
        return (List<JsonValue>) value;
    }

    private BigDecimal decimalOrNull() {
        if (type != Type.NUMBER && type != Type.STRING) {
            return null;
        }
        String text = type == Type.STRING ? ((String) value).trim() : (String) value;
        if (text.isEmpty() || text.length() > MAX_NUMBER_TEXT) {
            return null;
        }
        try {
            return new BigDecimal(text);
        } catch (NumberFormatException e) {
            // not a number, or an exponent beyond an int
            return null;
        }
    }

    private static String count(int n, String noun) {
        return n + " " + noun + (n == 1 ? "" : "s");
    }

    private static JsonValue readDocument(JsonParser parser, String subject) throws IOException {
        JsonToken first = parser.nextToken();
        if (first == null) {
            throw new IllegalArgumentException(subject + " is empty");
        }
        JsonValue root = readValue(parser, first, 1, subject);
        if (parser.nextToken() != null) {
            throw new IllegalArgumentException(subject + " has more than one JSON value"
                    + at(parser.currentTokenLocation()));
        }
        return root;
    }

    private static JsonValue readValue(JsonParser parser, JsonToken token, int depth, String subject)
            throws IOException {
        switch (token) {
            case START_OBJECT: {
                checkDepth(parser, depth, subject);
                Map<String, JsonValue> fields = new LinkedHashMap<String, JsonValue>();
                JsonToken next;
                while ((next = parser.nextToken()) == JsonToken.FIELD_NAME) {
                    String name = parser.currentName();
                    fields.put(name, readValue(parser, nextValue(parser, subject), depth + 1, subject));
                }
                if (next != JsonToken.END_OBJECT) {
                    throw cutShort(parser, subject);
                }
                return new JsonValue(Type.OBJECT, Collections.unmodifiableMap(fields));
            }
            case START_ARRAY: {
                checkDepth(parser, depth, subject);
                List<JsonValue> elements = new ArrayList<JsonValue>();
                JsonToken next;
                while ((next = parser.nextToken()) != JsonToken.END_ARRAY) {
                    if (next == null) {
                        throw cutShort(parser, subject);
                    }
                    elements.add(readValue(parser, next, depth + 1, subject));
                }
                return new JsonValue(Type.ARRAY, Collections.unmodifiableList(elements));
            }
            case VALUE_STRING:
                return new JsonValue(Type.STRING, parser.getText());
            case VALUE_NUMBER_INT:
            case VALUE_NUMBER_FLOAT:
                return new JsonValue(Type.NUMBER, parser.getText());
            case VALUE_TRUE:
                return TRUE;
            case VALUE_FALSE:
                return FALSE;
            case VALUE_NULL:
                return NULL;
            default:
                throw new IllegalArgumentException(subject + " is not valid JSON" + at(parser.currentTokenLocation()));
        }
    }

    private static JsonToken nextValue(JsonParser parser, String subject) throws IOException {
        JsonToken token = parser.nextToken();
        if (token == null) {
            throw cutShort(parser, subject);
        }
        return token;
    }

    private static void checkDepth(JsonParser parser, int depth, String subject) {
        if (depth > JsonSupport.MAX_DEPTH) {
            throw new IllegalArgumentException(subject + " nests deeper than " + JsonSupport.MAX_DEPTH + " levels"
                    + at(parser.currentTokenLocation()));
        }
    }

    private static IllegalArgumentException cutShort(JsonParser parser, String subject) {
        return new IllegalArgumentException(subject + " ends before the JSON value is complete"
                + at(parser.currentLocation()));
    }

    /** Never Jackson's message: it quotes the offending token, which may be an address. */
    private static IllegalArgumentException malformed(String subject, IOException e) {
        if (e instanceof StreamConstraintsException) {
            return new IllegalArgumentException(subject + " has a number, string or name longer than the parser "
                    + "allows, or nests too deeply");
        }
        String where = e instanceof JsonProcessingException ? at(((JsonProcessingException) e).getLocation()) : "";
        if (e instanceof JsonEOFException) {
            return new IllegalArgumentException(subject + " ends before the JSON value is complete" + where);
        }
        return new IllegalArgumentException(subject + " is not valid JSON" + where);
    }

    private static String at(JsonLocation location) {
        if (location == null || location.getLineNr() < 1) {
            return "";
        }
        return " (line " + location.getLineNr() + ", column " + location.getColumnNr() + ")";
    }
}
