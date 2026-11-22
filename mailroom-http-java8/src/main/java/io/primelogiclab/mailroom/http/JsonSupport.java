package io.primelogiclab.mailroom.http;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonFactoryBuilder;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.StreamWriteConstraints;
import com.fasterxml.jackson.core.StreamWriteFeature;
import com.fasterxml.jackson.core.json.JsonWriteFeature;

/** The one Jackson factory {@link JsonWriter} and {@link JsonValue} share; thread-safe once built. */
final class JsonSupport {

    /** The deepest nesting either side accepts. Provider requests and responses stay far below it. */
    static final int MAX_DEPTH = 100;

    static final JsonFactory FACTORY = new JsonFactoryBuilder()
            // keys come from provider responses: don't intern them into the JVM's string pool
            .disable(JsonFactory.Feature.INTERN_FIELD_NAMES)
            // never quote the input in a location; messages are ours anyway
            .disable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION)
            // the reader counts depth itself for a clear message, so Jackson's limit only backs it up
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(MAX_DEPTH + 1).build())
            .enable(StreamWriteFeature.STRICT_DUPLICATE_DETECTION)
            // JsonWriter checks that every scope is closed instead of closing them silently
            .disable(StreamWriteFeature.AUTO_CLOSE_CONTENT)
            // a cyclic Map or List ends here instead of in a StackOverflowError
            .streamWriteConstraints(StreamWriteConstraints.builder().maxNestingDepth(MAX_DEPTH).build())
            // characters outside the BMP as UTF-8, not as two \\u escapes
            .enable(JsonWriteFeature.COMBINE_UNICODE_SURROGATES_IN_UTF8)
            .build();

    private JsonSupport() {
    }

    /** Tells whether a string has a high surrogate without its low one, or a low one on its own. */
    static boolean hasBrokenSurrogate(CharSequence s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= s.length() || !Character.isLowSurrogate(s.charAt(i + 1))) {
                    return true;
                }
                i++;
            } else if (Character.isLowSurrogate(c)) {
                return true;
            }
        }
        return false;
    }
}
