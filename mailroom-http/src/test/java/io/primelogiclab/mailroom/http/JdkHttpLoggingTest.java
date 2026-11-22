package io.primelogiclab.mailroom.http;

import static org.assertj.core.api.Assertions.assertThat;

import io.primelogiclab.mailroom.tck.StubHttpServer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The JDK's HttpURLConnection logs request headers at FINE; the engine pins its logger to INFO unless the application
 * set a level, and warns once when the application asked for FINE. Every record from every logger is captured.
 */
class JdkHttpLoggingTest {

    private static final String KEY = "k-jdk-logging-canary-31f9c";

    private final Logger jdk = JdkHttpLogging.logger();
    private final Logger root = Logger.getLogger("");
    private final List<LogRecord> records = new CopyOnWriteArrayList<LogRecord>();
    private final Handler capture = new Handler() {
        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };
    private Level jdkBefore;
    private Level rootBefore;
    private StubHttpServer server;

    @BeforeEach
    void start() {
        jdkBefore = jdk.getLevel();
        rootBefore = root.getLevel();
        capture.setLevel(Level.ALL);
        root.setLevel(Level.ALL);
        root.addHandler(capture);
        JdkHttpLogging.resetForTests();
        server = StubHttpServer.start();
    }

    @AfterEach
    void stop() {
        server.close();
        root.removeHandler(capture);
        root.setLevel(rootBefore);
        jdk.setLevel(jdkBefore);
        JdkHttpLogging.resetForTests();
    }

    @Test
    void anInheritedLevelIsPinnedToInfoSoNoHeaderIsLogged() {
        jdk.setLevel(null);

        send();

        assertThat(jdk.getLevel()).isEqualTo(Level.INFO);
        assertThat(texts()).noneMatch(text -> text.contains(KEY));
        assertThat(warnings()).isEmpty();
    }

    @Test
    void aLevelTheApplicationSetIsLeftAloneWithOneWarning() {
        jdk.setLevel(Level.FINE);

        send();
        send();

        assertThat(jdk.getLevel()).isEqualTo(Level.FINE);
        // the guard is needed: at FINE the JDK really does log the key
        assertThat(texts()).anyMatch(text -> text.contains(KEY));
        assertThat(warnings()).containsExactly(JdkHttpLogging.JDK_LOGGER + " is at FINE or below, so the JDK logs "
                + "every request header, credentials included; set it to INFO or above");
    }

    @Test
    void aClearedLevelIsPinnedAgainOnTheNextRequest() {
        jdk.setLevel(null);
        send();
        assertThat(jdk.getLevel()).isEqualTo(Level.INFO);

        jdk.setLevel(null);     // as LogManager.readConfiguration() would
        send();

        assertThat(jdk.getLevel()).isEqualTo(Level.INFO);
        assertThat(texts()).noneMatch(text -> text.contains(KEY));
    }

    @Test
    void aLevelAboveFineIsLeftAloneQuietly() {
        jdk.setLevel(Level.CONFIG);

        send();

        assertThat(jdk.getLevel()).isEqualTo(Level.CONFIG);
        assertThat(warnings()).isEmpty();
        assertThat(texts()).noneMatch(text -> text.contains(KEY));
    }

    private void send() {
        HttpResponse response = HttpEngines.urlConnection().execute(HttpRequest.post(server.uri("/send"))
                .header("X-Api-Key", KEY).json("{}").build());
        assertThat(response.statusCode()).isEqualTo(200);
    }

    private List<String> texts() {
        SimpleFormatter formatter = new SimpleFormatter();
        List<String> texts = new ArrayList<String>();
        for (LogRecord record : records) {
            texts.add(String.valueOf(record.getMessage()));
            texts.add(formatter.formatMessage(record));
            if (record.getParameters() != null) {
                for (Object parameter : record.getParameters()) {
                    texts.add(String.valueOf(parameter));
                }
            }
        }
        return texts;
    }

    private List<String> warnings() {
        List<String> warnings = new ArrayList<String>();
        for (LogRecord record : records) {
            if ("io.primelogiclab.mailroom.http".equals(record.getLoggerName())) {
                assertThat(record.getLevel()).isEqualTo(Level.WARNING);
                assertThat(record.getThrown()).isNull();
                warnings.add(record.getMessage());
            }
        }
        return warnings;
    }
}
