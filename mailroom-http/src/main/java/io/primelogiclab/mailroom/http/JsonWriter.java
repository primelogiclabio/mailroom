package io.primelogiclab.mailroom.http;

import com.fasterxml.jackson.core.Base64Variants;
import com.fasterxml.jackson.core.JsonGenerationException;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.exc.StreamConstraintsException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Writes one JSON request body, field by field, as UTF-8. Single use: finish it with {@link #toBytes()} or
 * {@link #toJson()}.
 *
 * <pre>{@code
 * byte[] body = JsonWriter.object()
 *     .field("sender", email.from().format())
 *     .strings("to", recipients)
 *     .field("subject", email.subject())          // an Optional: written only when present
 *     .startArray("attachments")
 *         .startObject()
 *             .field("filename", "report.pdf")
 *             .base64("fileblob", attachment.content())
 *         .end()
 *     .end()
 *     .value("template_data", templateData)       // a Map, List, String, number, boolean or null
 *     .toBytes();
 * HttpRequest request = HttpRequest.post(uri).json(body).build();
 * }</pre>
 *
 * <p>Inside an object, write with the {@code field}, {@code strings}, {@code base64} and {@code value} methods, which
 * take a field name; inside an array, with the {@code item} methods. {@code startObject} and {@code startArray} open a
 * nested scope and {@link #end()} closes it; the outermost object or array is closed by {@link #toBytes()}.
 *
 * <p>The writer is strict, so a mapping bug shows up in the provider's tests rather than at the provider: a null
 * argument throws {@link NullPointerException} (use {@link #field(String, Optional)} for optional values), and writing
 * a field name twice in one object, writing in the wrong kind of scope, or finishing with a scope still open throws
 * {@link IllegalStateException}. Messages name types and field names, never values. After a call throws, the writer
 * may hold half a value: don't use it further.
 *
 * @since 0.1.0
 */
public final class JsonWriter {

    private final ByteArrayOutputStream out = new ByteArrayOutputStream(256);
    private final JsonGenerator generator;
    /** Open scopes, the outermost included. */
    private int depth = 1;
    private boolean finished;
    private boolean failed;

    private JsonWriter(boolean array) {
        try {
            generator = JsonSupport.FACTORY.createGenerator(out);
            if (array) {
                generator.writeStartArray();
            } else {
                generator.writeStartObject();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("writing to memory failed", e);
        }
    }

    /**
     * Starts a body that is a JSON object, the usual case.
     *
     * @return a writer inside the object
     * @since 0.1.0
     */
    public static JsonWriter object() {
        return new JsonWriter(false);
    }

    /**
     * Starts a body that is a JSON array, such as a batch of messages.
     *
     * @return a writer inside the array
     * @since 0.1.0
     */
    public static JsonWriter array() {
        return new JsonWriter(true);
    }

    /**
     * Writes a string field.
     *
     * @param name  the field name
     * @param value the value
     * @return this writer
     * @throws IllegalStateException    if the current scope is an array, or the object already has this field
     * @throws IllegalArgumentException if the name or value has a broken UTF-16 surrogate pair
     * @since 0.1.0
     */
    public JsonWriter field(String name, String value) {
        String n = name(name);
        String v = text(Objects.requireNonNull(value, "value"));
        inObject("field");
        try {
            generator.writeFieldName(n);
            generator.writeString(v);
        } catch (IOException e) {
            throw failed(e);
        }
        return this;
    }

    /**
     * Writes a string field if the value is present, and nothing otherwise.
     *
     * @param name  the field name
     * @param value the value, or empty to leave the field out
     * @return this writer
     * @throws IllegalStateException    if the current scope is an array, or the object already has this field
     * @throws IllegalArgumentException if the name or value has a broken UTF-16 surrogate pair
     * @since 0.1.0
     */
    public JsonWriter field(String name, Optional<String> value) {
        Objects.requireNonNull(value, "value");
        if (value.isPresent()) {
            return field(name, value.get());
        }
        name(name);
        inObject("field");
        return this;
    }

    /**
     * Writes a whole-number field.
     *
     * @param name  the field name
     * @param value the value
     * @return this writer
     * @throws IllegalStateException    if the current scope is an array, or the object already has this field
     * @throws IllegalArgumentException if the name has a broken UTF-16 surrogate pair
     * @since 0.1.0
     */
    public JsonWriter field(String name, long value) {
        String n = name(name);
        inObject("field");
        try {
            generator.writeFieldName(n);
            generator.writeNumber(value);
        } catch (IOException e) {
            throw failed(e);
        }
        return this;
    }

    /**
     * Writes a boolean field.
     *
     * @param name  the field name
     * @param value the value
     * @return this writer
     * @throws IllegalStateException    if the current scope is an array, or the object already has this field
     * @throws IllegalArgumentException if the name has a broken UTF-16 surrogate pair
     * @since 0.1.0
     */
    public JsonWriter field(String name, boolean value) {
        String n = name(name);
        inObject("field");
        try {
            generator.writeFieldName(n);
            generator.writeBoolean(value);
        } catch (IOException e) {
            throw failed(e);
        }
        return this;
    }

    /**
     * Writes a field holding an array of strings, such as a recipient list. An empty list writes an empty array.
     *
     * @param name   the field name
     * @param values the strings, none of them null
     * @return this writer
     * @throws IllegalStateException    if the current scope is an array, or the object already has this field
     * @throws IllegalArgumentException if the name or a value has a broken UTF-16 surrogate pair
     * @since 0.1.0
     */
    public JsonWriter strings(String name, Iterable<String> values) {
        String n = name(name);
        Objects.requireNonNull(values, "values");
        List<String> checked = new ArrayList<>();
        for (String value : values) {
            checked.add(text(Objects.requireNonNull(value, "values element")));
        }
        inObject("strings");
        try {
            generator.writeFieldName(n);
            generator.writeStartArray();
            for (String value : checked) {
                generator.writeString(value);
            }
            generator.writeEndArray();
        } catch (IOException e) {
            throw failed(e);
        }
        return this;
    }

    /**
     * Writes a field holding bytes as base64: the standard alphabet with padding and no line breaks, as attachment
     * fields expect.
     *
     * @param name  the field name
     * @param bytes the bytes
     * @return this writer
     * @throws IllegalStateException    if the current scope is an array, or the object already has this field
     * @throws IllegalArgumentException if the name has a broken UTF-16 surrogate pair
     * @since 0.1.0
     */
    public JsonWriter base64(String name, byte[] bytes) {
        String n = name(name);
        Objects.requireNonNull(bytes, "bytes");
        inObject("base64");
        try {
            generator.writeFieldName(n);
            generator.writeBinary(Base64Variants.MIME_NO_LINEFEEDS, bytes, 0, bytes.length);
        } catch (IOException e) {
            throw failed(e);
        }
        return this;
    }

    /**
     * Writes a field holding any plain value, such as caller-supplied template data:
     * <ul>
     *   <li>{@code null} as JSON null;</li>
     *   <li>a {@link CharSequence} as a string and a {@link Boolean} as a boolean;</li>
     *   <li>{@link Integer}, {@link Long}, {@link Short}, {@link Byte}, {@link BigInteger}, {@link BigDecimal}, and
     *       finite {@link Double} and {@link Float} values as numbers;</li>
     *   <li>a {@link Map} with {@code String} keys as an object, in the map's order;</li>
     *   <li>an {@link Iterable} or an object array as an array;</li>
     *   <li>a {@link JsonValue} as itself.</li>
     * </ul>
     *
     * @param name  the field name
     * @param value the value; nested values follow the same rules
     * @return this writer
     * @throws IllegalStateException    if the current scope is an array, or the object already has this field
     * @throws IllegalArgumentException if the value, or one inside it, is of another type (such as {@code byte[]},
     *                                  which {@link #base64(String, byte[])} writes, or an {@link Optional}), is NaN
     *                                  or infinite, is a missing {@link JsonValue}, has a non-String or null map key,
     *                                  has a broken UTF-16 surrogate pair, or nests deeper than 100 levels (a map or
     *                                  list that contains itself)
     * @since 0.1.0
     */
    public JsonWriter value(String name, Object value) {
        String n = name(name);
        inObject("value");
        try {
            generator.writeFieldName(n);
            writeAny(value);
        } catch (IOException e) {
            throw failed(e);
        } catch (RuntimeException e) {
            failed = true;
            throw e;
        }
        return this;
    }

    /**
     * Starts an object as the value of a field; close it with {@link #end()}.
     *
     * @param name the field name
     * @return this writer, now inside the new object
     * @throws IllegalStateException    if the current scope is an array, or the object already has this field
     * @throws IllegalArgumentException if the name has a broken UTF-16 surrogate pair, or the body would nest deeper
     *                                  than 100 levels
     * @since 0.1.0
     */
    public JsonWriter startObject(String name) {
        String n = name(name);
        inObject("startObject(name)");
        try {
            generator.writeFieldName(n);
            generator.writeStartObject();
        } catch (IOException e) {
            throw failed(e);
        }
        depth++;
        return this;
    }

    /**
     * Starts an array as the value of a field; close it with {@link #end()}.
     *
     * @param name the field name
     * @return this writer, now inside the new array
     * @throws IllegalStateException    if the current scope is an array, or the object already has this field
     * @throws IllegalArgumentException if the name has a broken UTF-16 surrogate pair, or the body would nest deeper
     *                                  than 100 levels
     * @since 0.1.0
     */
    public JsonWriter startArray(String name) {
        String n = name(name);
        inObject("startArray(name)");
        try {
            generator.writeFieldName(n);
            generator.writeStartArray();
        } catch (IOException e) {
            throw failed(e);
        }
        depth++;
        return this;
    }

    /**
     * Writes a string element.
     *
     * @param value the value
     * @return this writer
     * @throws IllegalStateException    if the current scope is an object
     * @throws IllegalArgumentException if the value has a broken UTF-16 surrogate pair
     * @since 0.1.0
     */
    public JsonWriter item(String value) {
        String v = text(Objects.requireNonNull(value, "value"));
        inArray("item");
        try {
            generator.writeString(v);
        } catch (IOException e) {
            throw failed(e);
        }
        return this;
    }

    /**
     * Writes a whole-number element.
     *
     * @param value the value
     * @return this writer
     * @throws IllegalStateException if the current scope is an object
     * @since 0.1.0
     */
    public JsonWriter item(long value) {
        inArray("item");
        try {
            generator.writeNumber(value);
        } catch (IOException e) {
            throw failed(e);
        }
        return this;
    }

    /**
     * Writes a boolean element.
     *
     * @param value the value
     * @return this writer
     * @throws IllegalStateException if the current scope is an object
     * @since 0.1.0
     */
    public JsonWriter item(boolean value) {
        inArray("item");
        try {
            generator.writeBoolean(value);
        } catch (IOException e) {
            throw failed(e);
        }
        return this;
    }

    /**
     * Writes an element holding any plain value, by the rules of {@link #value(String, Object)}.
     *
     * @param value the value
     * @return this writer
     * @throws IllegalStateException    if the current scope is an object
     * @throws IllegalArgumentException as for {@link #value(String, Object)}
     * @since 0.1.0
     */
    public JsonWriter itemValue(Object value) {
        inArray("itemValue");
        try {
            writeAny(value);
        } catch (IOException e) {
            throw failed(e);
        } catch (RuntimeException e) {
            failed = true;
            throw e;
        }
        return this;
    }

    /**
     * Starts an object as an element; close it with {@link #end()}.
     *
     * @return this writer, now inside the new object
     * @throws IllegalStateException    if the current scope is an object
     * @throws IllegalArgumentException if the body would nest deeper than 100 levels
     * @since 0.1.0
     */
    public JsonWriter startObject() {
        inArray("startObject()");
        try {
            generator.writeStartObject();
        } catch (IOException e) {
            throw failed(e);
        }
        depth++;
        return this;
    }

    /**
     * Starts an array as an element; close it with {@link #end()}.
     *
     * @return this writer, now inside the new array
     * @throws IllegalStateException    if the current scope is an object
     * @throws IllegalArgumentException if the body would nest deeper than 100 levels
     * @since 0.1.0
     */
    public JsonWriter startArray() {
        inArray("startArray()");
        try {
            generator.writeStartArray();
        } catch (IOException e) {
            throw failed(e);
        }
        depth++;
        return this;
    }

    /**
     * Closes the innermost object or array that a {@code startObject} or {@code startArray} call opened.
     *
     * @return this writer, back in the enclosing scope
     * @throws IllegalStateException if only the outermost scope is open; {@link #toBytes()} closes that one
     * @since 0.1.0
     */
    public JsonWriter end() {
        usable();
        if (depth == 1) {
            throw new IllegalStateException("nothing to end: only the outermost "
                    + (generator.getOutputContext().inArray() ? "array" : "object")
                    + " is open, and toBytes() or toJson() closes it");
        }
        try {
            if (generator.getOutputContext().inArray()) {
                generator.writeEndArray();
            } else {
                generator.writeEndObject();
            }
        } catch (IOException e) {
            throw failed(e);
        }
        depth--;
        return this;
    }

    /**
     * Closes the outermost object or array and returns the body.
     *
     * @return the JSON as UTF-8 bytes
     * @throws IllegalStateException if a nested object or array is still open, or the writer is already finished
     * @since 0.1.0
     */
    public byte[] toBytes() {
        usable();
        if (depth > 1) {
            int open = depth - 1;
            throw new IllegalStateException(open + (open == 1 ? " nested object or array is" : " nested objects or "
                    + "arrays are") + " still open; close " + (open == 1 ? "it" : "them") + " with end()");
        }
        try {
            if (generator.getOutputContext().inArray()) {
                generator.writeEndArray();
            } else {
                generator.writeEndObject();
            }
            generator.close();
        } catch (IOException e) {
            throw failed(e);
        }
        finished = true;
        return out.toByteArray();
    }

    /**
     * Closes the outermost object or array and returns the body as text.
     *
     * @return the JSON
     * @throws IllegalStateException if a nested object or array is still open, or the writer is already finished
     * @since 0.1.0
     */
    public String toJson() {
        return new String(toBytes(), StandardCharsets.UTF_8);
    }

    /**
     * Describes the writer's state without its content, such as {@code JsonWriter[open, depth 2]}.
     *
     * @return a description
     */
    @Override
    public String toString() {
        return "JsonWriter[" + (finished ? "finished" : failed ? "failed" : "open, depth " + depth) + "]";
    }

    private void writeAny(Object value) throws IOException {
        if (value == null) {
            generator.writeNull();
        } else if (value instanceof CharSequence) {
            generator.writeString(text(value.toString()));
        } else if (value instanceof Boolean) {
            generator.writeBoolean((Boolean) value);
        } else if (value instanceof Integer || value instanceof Long || value instanceof Short
                || value instanceof Byte) {
            generator.writeNumber(((Number) value).longValue());
        } else if (value instanceof BigInteger) {
            generator.writeNumber((BigInteger) value);
        } else if (value instanceof BigDecimal) {
            generator.writeNumber((BigDecimal) value);
        } else if (value instanceof Double || value instanceof Float) {
            double d = ((Number) value).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                throw new IllegalArgumentException("JSON has no NaN or infinity");
            }
            if (value instanceof Float) {
                generator.writeNumber((Float) value);
            } else {
                generator.writeNumber(d);
            }
        } else if (value instanceof Map) {
            generator.writeStartObject();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                Object key = entry.getKey();
                if (!(key instanceof String)) {
                    throw new IllegalArgumentException(key == null ? "a map key is null"
                            : "a map key must be a String, not " + typeName(key));
                }
                generator.writeFieldName(text((String) key));
                writeAny(entry.getValue());
            }
            generator.writeEndObject();
        } else if (value instanceof Iterable) {
            generator.writeStartArray();
            for (Object element : (Iterable<?>) value) {
                writeAny(element);
            }
            generator.writeEndArray();
        } else if (value instanceof Object[]) {
            generator.writeStartArray();
            for (Object element : (Object[]) value) {
                writeAny(element);
            }
            generator.writeEndArray();
        } else if (value instanceof JsonValue) {
            ((JsonValue) value).writeTo(generator);
        } else if (value instanceof byte[]) {
            throw new IllegalArgumentException("write byte[] with base64(name, bytes)");
        } else if (value instanceof Optional) {
            throw new IllegalArgumentException("unwrap the Optional first; field(name, Optional) leaves an empty one "
                    + "out");
        } else {
            throw new IllegalArgumentException("cannot write a " + typeName(value) + " as JSON; convert it to a "
                    + "String, number, Boolean, Map or List first");
        }
    }

    private static String typeName(Object value) {
        String name = value.getClass().getCanonicalName();
        return name == null ? value.getClass().getName() : name;
    }

    private void usable() {
        if (finished) {
            throw new IllegalStateException("this JsonWriter is finished");
        }
        if (failed) {
            throw new IllegalStateException("this JsonWriter failed earlier and may hold half a value");
        }
    }

    private void inObject(String method) {
        usable();
        if (!generator.getOutputContext().inObject()) {
            throw new IllegalStateException(method + " writes a named field, but the current scope is an array; "
                    + "use the item methods there");
        }
    }

    private void inArray(String method) {
        usable();
        if (!generator.getOutputContext().inArray()) {
            throw new IllegalStateException(method + " writes an array element, but the current scope is an object; "
                    + "use the methods that take a field name there");
        }
    }

    private static String name(String name) {
        return text(Objects.requireNonNull(name, "name"));
    }

    private static String text(String text) {
        if (JsonSupport.hasBrokenSurrogate(text)) {
            throw new IllegalArgumentException("a string has a broken UTF-16 surrogate pair");
        }
        return text;
    }

    private RuntimeException failed(IOException e) {
        failed = true;
        if (e instanceof StreamConstraintsException) {
            return new IllegalArgumentException("the JSON nests deeper than " + JsonSupport.MAX_DEPTH
                    + " levels; does a map or list contain itself?");
        }
        if (e instanceof JsonGenerationException) {
            // Jackson's writer messages name fields, not values, such as "Duplicate field 'to'"
            return new IllegalStateException(((JsonGenerationException) e).getOriginalMessage());
        }
        return new UncheckedIOException("writing to memory failed", e);
    }
}
