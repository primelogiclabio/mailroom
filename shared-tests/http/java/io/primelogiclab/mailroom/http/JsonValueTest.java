package io.primelogiclab.mailroom.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class JsonValueTest {

    /** An address that must never reach an error message. */
    private static final String CANARY = "canary.person@example.com";

    /** SMTP2GO's send response (fixture shape from the old repo) plus fields a later API version might add. */
    private static final String SMTP2GO_OK = "{\"request_id\":\"00000000-0000-4000-8000-000000000007\","
            + "\"data\":{\"succeeded\":1,\"failed\":0,\"failures\":[],\"email_id\":\"1Exm02-FixtureMail-0002\","
            + "\"new_field\":{\"nested\":[1,2,{\"deep\":true}]}},\"api_version\":\"3.1\"}";

    @Test
    void readsKnownFieldsAndIgnoresUnknownOnes() {
        JsonValue json = JsonValue.parse(SMTP2GO_OK);
        JsonValue data = json.get("data");

        assertThat(data.get("email_id").asString()).contains("1Exm02-FixtureMail-0002");
        assertThat(data.get("succeeded").asLong()).hasValue(1);
        assertThat(data.get("failed").asLong()).hasValue(0);
        assertThat(data.get("failures").elements()).isEmpty();
        assertThat(json.get("request_id").asString()).contains("00000000-0000-4000-8000-000000000007");
        assertThat(json.type()).isEqualTo(JsonValue.Type.OBJECT);
        assertThat(json.fields().keySet()).containsExactly("request_id", "data", "api_version");
    }

    @Test
    void readsAnSmtp2goErrorBody() {
        JsonValue json = JsonValue.parse("{\"request_id\":\"r-1\",\"data\":{\"error\":\"You do not have permission"
                + " to access this API endpoint\",\"error_code\":\"E_ApiResponseCodes.ENDPOINT_PERMISSION_DENIED\"}}");

        assertThat(json.get("data").get("error_code").asString())
                .contains("E_ApiResponseCodes.ENDPOINT_PERMISSION_DENIED");
        assertThat(json.get("data").get("email_id").asString()).isEmpty();
    }

    @Test
    void navigatingPastAnythingAbsentIsMissingNeverAnException() {
        JsonValue json = JsonValue.parse("{\"a\":{\"b\":[10,20]},\"s\":\"text\",\"n\":null}");

        assertThat(json.get("a").get("b").get(1).asLong()).hasValue(20);
        assertThat(json.get("a").get("b").get(2).isMissing()).isTrue();
        assertThat(json.get("a").get("b").get(-1).isMissing()).isTrue();
        assertThat(json.get("nope").get("deeper").get(0).asString()).isEmpty();
        assertThat(json.get("s").get("x").isMissing()).isTrue();
        assertThat(json.get("s").get(0).isMissing()).isTrue();
        assertThat(json.get(0).isMissing()).isTrue();
        assertThat(json.get("n").isNull()).isTrue();
        assertThat(json.get("n").isMissing()).isFalse();
        assertThat(json.get("nope").isNull()).isFalse();
        assertThat(json.get("nope").type()).isEqualTo(JsonValue.Type.MISSING);
        assertThat(json.get("nope").fields()).isEmpty();
        assertThat(json.get("nope").elements()).isEmpty();
        assertThat(json.get("nope").size()).isZero();
        assertThat(json.get("nope").asLong()).isEmpty();
        assertThat(json.get("nope").asDecimal()).isEmpty();
        assertThat(json.get("nope").asBoolean()).isEmpty();
        assertThat(json.get("s").fields()).isEmpty();
        assertThat(json.get("s").elements()).isEmpty();
    }

    @Test
    void convertsWhereTheMeaningIsPlain() {
        JsonValue json = JsonValue.parse("{\"id\":12345,\"idText\":\"12345\",\"padded\":\" 7 \",\"whole\":12.0,"
                + "\"frac\":1.5,\"big\":123456789012345678901234567890,\"exp\":1e3,\"huge\":1e999999999,"
                + "\"t\":true,\"f\":false,\"tText\":\"TRUE\",\"word\":\"yes\",\"obj\":{},\"nul\":null}");

        assertThat(json.get("id").asString()).contains("12345");
        assertThat(json.get("id").asLong()).hasValue(12345);
        assertThat(json.get("idText").asLong()).hasValue(12345);
        assertThat(json.get("padded").asLong()).hasValue(7);
        assertThat(json.get("whole").asLong()).hasValue(12);
        assertThat(json.get("frac").asLong()).isEmpty();
        assertThat(json.get("frac").asDecimal()).contains(new BigDecimal("1.5"));
        assertThat(json.get("big").asLong()).isEmpty();
        assertThat(json.get("big").asDecimal()).contains(new BigDecimal("123456789012345678901234567890"));
        assertThat(json.get("exp").asLong()).hasValue(1000);
        assertThat(json.get("exp").asString()).contains("1e3");
        assertThat(json.get("huge").asLong()).isEmpty();
        assertThat(json.get("t").asBoolean()).contains(true);
        assertThat(json.get("f").asBoolean()).contains(false);
        assertThat(json.get("t").asString()).contains("true");
        assertThat(json.get("tText").asBoolean()).contains(true);
        assertThat(json.get("word").asBoolean()).isEmpty();
        assertThat(json.get("word").asLong()).isEmpty();
        assertThat(json.get("t").asLong()).isEmpty();
        assertThat(json.get("obj").asString()).isEmpty();
        assertThat(json.get("nul").asString()).isEmpty();
        assertThat(json.get("nul").asBoolean()).isEmpty();
    }

    @Test
    void parsesEveryKindOfRootValue() {
        assertThat(JsonValue.parse(" [1, \"two\", null] ").elements()).hasSize(3);
        assertThat(JsonValue.parse("\"just text\"").asString()).contains("just text");
        assertThat(JsonValue.parse("42").asLong()).hasValue(42);
        assertThat(JsonValue.parse("true").asBoolean()).contains(true);
        assertThat(JsonValue.parse("null").isNull()).isTrue();
        assertThat(JsonValue.parse("{}").size()).isZero();
    }

    @Test
    void readsBytesInUtf8AndOtherUnicodeEncodings() {
        String text = "{\"name\":\"Zoë 😀 \\u00e9\"}";
        assertThat(JsonValue.parse(text.getBytes(StandardCharsets.UTF_8)).get("name").asString())
                .contains("Zoë 😀 é");
        assertThat(JsonValue.parse(text.getBytes(StandardCharsets.UTF_16LE)).get("name").asString())
                .contains("Zoë 😀 é");
        assertThat(JsonValue.parse(text.getBytes(Charset.forName("UTF-32BE"))).get("name").asString())
                .contains("Zoë 😀 é");
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, '{', '}'};
        assertThat(JsonValue.parse(bom).size()).isZero();
    }

    @Test
    void theLastOfDuplicateKeysWins() {
        JsonValue json = JsonValue.parse("{\"a\":1,\"b\":2,\"a\":3}");
        assertThat(json.get("a").asLong()).hasValue(3);
        assertThat(json.fields().keySet()).containsExactly("a", "b");
    }

    @Test
    void malformedInputFailsClearlyWithoutQuotingIt() {
        assertMalformed("", "the input is empty");
        assertMalformed("   \n ", "the input is empty");
        assertMalformed("{\"to\":\"" + CANARY + "\"", "the input ends before the JSON value is complete");
        assertMalformed("{\"to\":[\"" + CANARY + "\",", "the input is not valid JSON (line 1, column 36)");
        assertMalformed("{\"to\":\"" + CANARY, "the input ends before the JSON value is complete");
        assertMalformed("{\"to\":" + CANARY + "}", "the input is not valid JSON (line 1, column ");
        assertMalformed(CANARY, "the input is not valid JSON (line 1, column ");
        assertMalformed("{'to':'" + CANARY + "'}", "the input is not valid JSON (line 1, column ");
        assertMalformed("{\"to\":\"" + CANARY + "\"} trailing", "the input is not valid JSON (line 1, column ");
        assertMalformed("{\"to\":\"" + CANARY + "\"}{}", "the input has more than one JSON value (line 1, column ");
        assertMalformed("<html><body>502 Bad Gateway " + CANARY + "</body></html>",
                "the input is not valid JSON (line 1, column ");
        assertMalformed("{\"n\":NaN}", "the input is not valid JSON (line 1, column ");
        assertMalformed("{\"n\":01}", "the input is not valid JSON (line 1, column ");
        assertMalformed("{\n  \"a\": 1,\n  \"b\": oops\n}", "the input is not valid JSON (line 3, column ");
    }

    @Test
    void invalidUtf8IsMalformed() {
        byte[] bytes = {'{', '"', 'a', '"', ':', '"', (byte) 0xC3, (byte) 0x28, '"', '}'};
        assertThatThrownBy(() -> JsonValue.parse(bytes))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("the input is not valid JSON")
                .hasNoCause();
    }

    @Test
    void nestingIsLimitedTo100Levels() {
        assertThat(JsonValue.parse(nested(100)).type()).isEqualTo(JsonValue.Type.ARRAY);
        assertThatThrownBy(() -> JsonValue.parse(nested(101)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("the input nests deeper than 100 levels (line 1, column ")
                .hasNoCause();
        assertThatThrownBy(() -> JsonValue.parse(nested(5000)))
                .hasMessageStartingWith("the input nests deeper than 100 levels");
    }

    @Test
    void equalityIgnoresFieldOrderAndComparesNumbersByValue() {
        JsonValue a = JsonValue.parse("{\"x\":1,\"y\":[true,null,\"s\"],\"z\":{\"n\":1.50}}");
        JsonValue b = JsonValue.parse("{\"z\":{\"n\":1.5},\"y\":[true,null,\"s\"],\"x\":1.0}");

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
        assertThat(JsonValue.parse("0")).isEqualTo(JsonValue.parse("-0.000"));
        assertThat(JsonValue.parse("0").hashCode()).isEqualTo(JsonValue.parse("-0.000").hashCode());
        assertThat(JsonValue.parse("1e2")).isEqualTo(JsonValue.parse("100"));
        assertThat(JsonValue.parse("[1,2]")).isNotEqualTo(JsonValue.parse("[2,1]"));
        assertThat(JsonValue.parse("\"1\"")).isNotEqualTo(JsonValue.parse("1"));
        assertThat(JsonValue.parse("null")).isNotEqualTo(a.get("missing"));
        assertThat(a.get("missing")).isEqualTo(b.get("other"));
    }

    @Test
    void toStringHidesTheContent() {
        JsonValue json = JsonValue.parse("{\"to\":\"" + CANARY + "\",\"list\":[\"" + CANARY + "\"],\"n\":5,"
                + "\"one\":[1],\"b\":true,\"z\":null}");

        assertThat(json.toString()).isEqualTo("JsonValue[object, 6 fields]");
        assertThat(json.get("to").toString()).isEqualTo("JsonValue[string, 25 chars]");
        assertThat(json.get("list").toString()).isEqualTo("JsonValue[array, 1 element]");
        assertThat(json.get("n").toString()).isEqualTo("JsonValue[number]");
        assertThat(json.get("b").toString()).isEqualTo("JsonValue[boolean]");
        assertThat(json.get("z").toString()).isEqualTo("JsonValue[null]");
        assertThat(json.get("nope").toString()).isEqualTo("JsonValue[missing]");
    }

    @Test
    void toJsonWritesCompactJsonThatParsesBackEqual() {
        String text = "{ \"a\" : [ 1 , 2.50 , -3e-2 ] , \"b\" : { \"c\" : \"line\\nbreak \\\"q\\\" 😀\" },"
                + " \"d\" : null , \"e\" : false }";
        JsonValue json = JsonValue.parse(text);

        assertThat(json.toJson())
                .isEqualTo("{\"a\":[1,2.50,-3e-2],\"b\":{\"c\":\"line\\nbreak \\\"q\\\" 😀\"},\"d\":null,\"e\":false}");
        assertThat(JsonValue.parse(json.toJson())).isEqualTo(json);
        assertThatThrownBy(() -> json.get("nope").toJson())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("a missing value has no JSON form");
    }

    @Test
    void parsedValuesCannotBeChanged() {
        JsonValue json = JsonValue.parse("{\"a\":[1]}");
        assertThatThrownBy(() -> json.fields().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> json.get("a").elements().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    private static void assertMalformed(String input, String messageStart) {
        for (Object form : Arrays.asList(input, input.getBytes(StandardCharsets.UTF_8))) {
            assertThatThrownBy(() -> {
                if (form instanceof String) {
                    JsonValue.parse((String) form);
                } else {
                    JsonValue.parse((byte[]) form);
                }
            })
                    .as(input)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageStartingWith(messageStart)
                    .hasNoCause()
                    .hasMessageNotContaining("canary")
                    .hasMessageNotContaining("example");
        }
    }

    private static String nested(int depth) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < depth; i++) {
            s.append('[');
        }
        for (int i = 0; i < depth; i++) {
            s.append(']');
        }
        return s.toString();
    }
}
