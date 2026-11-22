package io.primelogiclab.mailroom.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import org.junit.jupiter.api.Test;

class JsonWriterTest {

    private static final String CANARY = "canary.person@example.com";

    @Test
    void writesAnSmtp2goStyleRequest() {
        byte[] pdf = {0x25, 0x50, 0x44, 0x46, (byte) 0xFF, 0x00};
        String json = JsonWriter.object()
                .field("sender", "Sender <sender@example.com>")
                .strings("to", Arrays.asList("a@example.com", "b@example.com"))
                .strings("cc", Collections.<String>emptyList())
                .field("subject", Optional.of("Hello"))
                .field("html_body", Optional.<String>empty())
                .startArray("custom_headers")
                    .startObject().field("header", "X-Mailroom-Tag").field("value", "welcome").end()
                .end()
                .startArray("attachments")
                    .startObject().field("filename", "a.pdf").base64("fileblob", pdf).end()
                .end()
                .toJson();

        assertThat(json).isEqualTo("{\"sender\":\"Sender <sender@example.com>\","
                + "\"to\":[\"a@example.com\",\"b@example.com\"],\"cc\":[],\"subject\":\"Hello\","
                + "\"custom_headers\":[{\"header\":\"X-Mailroom-Tag\",\"value\":\"welcome\"}],"
                + "\"attachments\":[{\"filename\":\"a.pdf\",\"fileblob\":\"" + Base64.getEncoder().encodeToString(pdf)
                + "\"}]}");
    }

    @Test
    void writesEveryPlainFieldType() {
        String json = JsonWriter.object()
                .field("s", "text")
                .field("l", Long.MIN_VALUE)
                .field("i", 7)
                .field("t", true)
                .field("f", false)
                .startObject("o").end()
                .startArray("a").end()
                .toJson();

        assertThat(json).isEqualTo("{\"s\":\"text\",\"l\":" + Long.MIN_VALUE
                + ",\"i\":7,\"t\":true,\"f\":false,\"o\":{},\"a\":[]}");
    }

    @Test
    void writesArrayBodiesAndItems() {
        String json = JsonWriter.array()
                .item("a").item(2).item(true)
                .itemValue(null)
                .startArray().item(1).end()
                .startObject().field("k", "v").end()
                .toJson();

        assertThat(json).isEqualTo("[\"a\",2,true,null,[1],{\"k\":\"v\"}]");
        assertThat(JsonWriter.array().toJson()).isEqualTo("[]");
        assertThat(JsonWriter.object().toJson()).isEqualTo("{}");
    }

    @Test
    void writesPlainValueTrees() {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("name", "Zoë");
        data.put("count", 3);
        data.put("long", 4L);
        data.put("short", (short) 5);
        data.put("byte", (byte) 6);
        data.put("big", new BigInteger("123456789012345678901234567890"));
        data.put("price", new BigDecimal("19.90"));
        data.put("ratio", 0.25d);
        data.put("half", 0.5f);
        data.put("ok", Boolean.TRUE);
        data.put("none", null);
        data.put("builder", new StringBuilder("sb"));
        data.put("list", Arrays.asList(1, "two", null));
        data.put("array", new Object[] {"x", Collections.singletonMap("y", 1)});
        data.put("parsed", JsonValue.parse("{\"p\":[true]}"));

        String json = JsonWriter.object().value("template_data", data).toJson();

        assertThat(json).isEqualTo("{\"template_data\":{\"name\":\"Zoë\",\"count\":3,\"long\":4,\"short\":5,"
                + "\"byte\":6,\"big\":123456789012345678901234567890,\"price\":19.90,\"ratio\":0.25,\"half\":0.5,"
                + "\"ok\":true,\"none\":null,\"builder\":\"sb\",\"list\":[1,\"two\",null],"
                + "\"array\":[\"x\",{\"y\":1}],\"parsed\":{\"p\":[true]}}}");
    }

    @Test
    void escapesTextAndRoundTripsThroughTheReader() {
        String tricky = "quote \" backslash \\ slash / tab \t newline \n nul \u0000 bell \u0007 "
                + "é 中文 emoji 😀 line-sep  ";
        byte[] bytes = JsonWriter.object().field(tricky, tricky).toBytes();
        String json = new String(bytes, StandardCharsets.UTF_8);

        assertThat(json).contains("\\\"", "\\\\", "\\t", "\\n", "\\u0000", "\\u0007", "😀");
        assertThat(json).doesNotContain("\\uD83D");
        assertThat(JsonValue.parse(bytes).get(tricky).asString()).contains(tricky);
    }

    @Test
    void base64MatchesTheJdkEncoderForEveryLength() {
        Random random = new Random(42);
        for (int length = 0; length < 200; length++) {
            byte[] bytes = new byte[length];
            random.nextBytes(bytes);
            JsonValue json = JsonValue.parse(JsonWriter.object().base64("b", bytes).toBytes());
            assertThat(json.get("b").asString()).as("length %d", length)
                    .contains(Base64.getEncoder().encodeToString(bytes));
        }
        byte[] large = new byte[100_000];
        random.nextBytes(large);
        String encoded = JsonValue.parse(JsonWriter.object().base64("b", large).toBytes()).get("b").asString().get();
        assertThat(encoded).doesNotContain("\n", "\r").isEqualTo(Base64.getEncoder().encodeToString(large));
    }

    @Test
    void writerOutputParsesBackToTheSameTree() {
        JsonValue expected = JsonValue.parse("{\"a\":[1,2.5,{\"b\":null}],\"c\":\"d\",\"e\":true}");
        byte[] written = JsonWriter.object()
                .startArray("a").item(1).itemValue(new BigDecimal("2.5")).startObject().value("b", null).end().end()
                .field("c", "d")
                .field("e", true)
                .toBytes();
        assertThat(JsonValue.parse(written)).isEqualTo(expected);
        assertThat(JsonWriter.object().value("copy", expected).toJson()).isEqualTo("{\"copy\":" + expected.toJson()
                + "}");
    }

    @Test
    void catchesMistakesInTheWritingCode() {
        assertThatThrownBy(() -> JsonWriter.object().field("to", "a").field("to", "b"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate field 'to'");
        assertThatThrownBy(() -> JsonWriter.object().item("x"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("item writes an array element, but the current scope is an object; use the methods that "
                        + "take a field name there");
        assertThatThrownBy(() -> JsonWriter.array().field("k", "v"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("field writes a named field, but the current scope is an array; use the item methods "
                        + "there");
        assertThatThrownBy(() -> JsonWriter.array().field("k", Optional.<String>empty()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> JsonWriter.object().end())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("nothing to end: only the outermost object is open, and toBytes() or toJson() closes it");
        assertThatThrownBy(() -> JsonWriter.object().startObject("a").startArray("b").toBytes())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("2 nested objects or arrays are still open; close them with end()");
        assertThatThrownBy(() -> JsonWriter.array().startObject().toJson())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("1 nested object or array is still open; close it with end()");
        assertThatThrownBy(() -> JsonWriter.object().field("s", (String) null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("value");
        assertThatThrownBy(() -> JsonWriter.object().strings("to", Arrays.asList("a", null)))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void aFinishedOrFailedWriterRefusesMoreWork() {
        JsonWriter finished = JsonWriter.object();
        finished.toBytes();
        assertThatThrownBy(finished::toBytes)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("this JsonWriter is finished");
        assertThatThrownBy(() -> finished.field("a", "b")).isInstanceOf(IllegalStateException.class);
        assertThat(finished.toString()).isEqualTo("JsonWriter[finished]");

        JsonWriter failed = JsonWriter.object();
        assertThatThrownBy(() -> failed.value("v", Arrays.asList(1, Instant.EPOCH)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(failed::toJson)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("this JsonWriter failed earlier and may hold half a value");
        assertThat(failed.toString()).isEqualTo("JsonWriter[failed]");
    }

    @Test
    void refusesValuesJsonCannotHoldWithoutShowingThem() {
        assertRefused(Double.NaN, "JSON has no NaN or infinity");
        assertRefused(Float.NEGATIVE_INFINITY, "JSON has no NaN or infinity");
        assertRefused(new byte[] {1}, "write byte[] with base64(name, bytes)");
        assertRefused(Optional.of(CANARY), "unwrap the Optional first; field(name, Optional) leaves an empty one out");
        assertRefused(Instant.EPOCH, "cannot write a java.time.Instant as JSON; convert it to a String, number, "
                + "Boolean, Map or List first");
        assertRefused(new int[] {1}, "cannot write a int[] as JSON; convert it to a String, number, Boolean, Map or "
                + "List first");
        assertRefused(Collections.singletonMap(1, CANARY), "a map key must be a String, not java.lang.Integer");
        assertRefused(Collections.singletonMap(null, CANARY), "a map key is null");
        assertRefused(JsonValue.parse("{}").get("missing"), "a missing JsonValue cannot be written");
        assertRefused("broken \uD83D surrogate " + CANARY, "a string has a broken UTF-16 surrogate pair");
        assertRefused("lone low \uDE00", "a string has a broken UTF-16 surrogate pair");
        assertThatThrownBy(() -> JsonWriter.object().field("x", "ends high \uD83D"))
                .hasMessage("a string has a broken UTF-16 surrogate pair");
        assertThatThrownBy(() -> JsonWriter.object().field("\uDE00", "v"))
                .hasMessage("a string has a broken UTF-16 surrogate pair");
    }

    @Test
    void aMapThatContainsItselfStopsAt100Levels() {
        Map<String, Object> cyclic = new LinkedHashMap<String, Object>();
        cyclic.put("self", cyclic);
        assertThatThrownBy(() -> JsonWriter.object().value("v", cyclic))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("the JSON nests deeper than 100 levels; does a map or list contain itself?");

        List<Object> list = new ArrayList<Object>();
        list.add(list);
        assertThatThrownBy(() -> JsonWriter.array().itemValue(list))
                .hasMessage("the JSON nests deeper than 100 levels; does a map or list contain itself?");

        JsonWriter deep = JsonWriter.array();
        for (int i = 1; i < 100; i++) {
            deep.startArray();
        }
        assertThatThrownBy(deep::startArray).hasMessageStartingWith("the JSON nests deeper than 100 levels");
    }

    @Test
    void toStringHidesTheContent() {
        JsonWriter writer = JsonWriter.object().field("to", CANARY).startArray("list");
        assertThat(writer.toString()).isEqualTo("JsonWriter[open, depth 2]");
    }

    @Test
    void feedsHttpRequestWithoutAStringRoundTrip() {
        byte[] body = JsonWriter.object().field("subject", "Zoë").toBytes();
        HttpRequest request = HttpRequest.post(java.net.URI.create("https://api.example.com/send")).json(body).build();

        assertThat(request.header("content-type")).contains("application/json; charset=utf-8");
        assertThat(request.body()).isEqualTo(body);
        body[0] = 'x';
        assertThat(request.body()[0]).isEqualTo((byte) '{');
    }

    private static void assertRefused(Object value, String message) {
        assertThatThrownBy(() -> JsonWriter.object().value("v", value))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(message)
                .hasNoCause();
        assertThatThrownBy(() -> JsonWriter.array().itemValue(Collections.singletonList(value)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(message);
    }
}
