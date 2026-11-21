package io.primelogiclab.mailroom.routing;

import io.primelogiclab.mailroom.Attempt;
import io.primelogiclab.mailroom.Email;
import io.primelogiclab.mailroom.EmailExtension;
import io.primelogiclab.mailroom.SendFailedException;
import io.primelogiclab.mailroom.internal.Router;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Shared helpers for the routing tests. */
final class Routing {

    private Routing() {
    }

    /** Records each wait instead of sleeping. */
    static final class RecordingSleeper implements Router.Sleeper {
        final List<Duration> waits = new CopyOnWriteArrayList<Duration>();

        @Override
        public void sleep(Duration duration) {
            waits.add(duration);
        }
    }

    /** A provider template id: essential, and it supplies the content. */
    static final class SendGridOptions implements EmailExtension {
        @Override
        public boolean essential() {
            return true;
        }

        @Override
        public boolean providesContent() {
            return true;
        }
    }

    static Email email() {
        return Email.builder().to("player@example.com").subject("Hi").text("Hello").build();
    }

    static SendFailedException failure(Runnable send) {
        try {
            send.run();
        } catch (SendFailedException e) {
            return e;
        }
        throw new AssertionError("expected a SendFailedException");
    }

    /** "transport KIND" for each attempt, such as "primary UNAVAILABLE" or "backup SUCCEEDED". */
    static List<String> describe(List<Attempt> attempts) {
        List<String> out = new ArrayList<String>();
        for (Attempt a : attempts) {
            out.add(a.transportName() + " " + (a.succeeded() ? "SUCCEEDED" : a.failureKind().get().name()));
        }
        return out;
    }
}
