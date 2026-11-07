package io.primelogiclab.mailroom;

import java.io.Console;
import java.io.PrintStream;
import java.net.InetAddress;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * Command-line tool that sends a test email, to check SMTP settings before wiring them into an app.
 * Run it with the mailroom jar and its mail library jars on the classpath:
 * {@code java -cp "mailroom-<build>-<version>.jar:lib/*" io.primelogiclab.mailroom.MailroomCli --help}.
 *
 * <p>Exit codes: 0 sent, 1 sending failed, 2 bad arguments.
 */
public final class MailroomCli {

    /** Environment variable holding the SMTP password, so it stays out of shell history. */
    public static final String PASSWORD_ENV = "MAILROOM_PASSWORD";

    static final int OK = 0;
    static final int SEND_FAILED = 1;
    static final int USAGE = 2;

    private static final String HELP = String.join(System.lineSeparator(),
            "Usage: java -cp <mailroom jar and its dependencies> io.primelogiclab.mailroom.MailroomCli",
            "         --host <host> --from <address> --to <address> [options]",
            "",
            "Sends a test email to check SMTP settings.",
            "",
            "Required:",
            "  --host <host>         SMTP server",
            "  --from <address>      Sender address",
            "  --to <address>        Recipient; repeat or comma-separate for several",
            "",
            "Options:",
            "  --port <port>         Default: 25, 587 or 465 to match --security",
            "  --security <mode>     none | starttls | tls   (default: starttls)",
            "  --user <name>         Log in as this user. The password is read from the",
            "                        " + PASSWORD_ENV + " environment variable, or prompted for.",
            "  --subject <text>      Default: \"mailroom test email\"",
            "  --text <text>         Body. Default: a short note with the time and server",
            "  --count <n>           Send n emails over one connection (default: 1)",
            "  --attempts <n>        Tries per email before giving up (default: 3)",
            "  --trust-any-cert      Accept any TLS certificate. Only for test servers with",
            "                        self-signed certificates; never in production.",
            "  --verbose             Log connection details, the server's replies and the SMTP",
            "                        conversation (passwords are never shown)",
            "  --help                Show this help",
            "",
            "Exit codes: 0 sent, 1 sending failed, 2 bad arguments.");

    private MailroomCli() {
    }

    public static void main(String[] args) {
        // One line per log message ("INFO: ..."), unless the user configured logging themselves.
        if (System.getProperty("java.util.logging.SimpleFormatter.format") == null
                && System.getProperty("java.util.logging.config.file") == null) {
            System.setProperty("java.util.logging.SimpleFormatter.format", "%4$s: %5$s%6$s%n");
        }
        System.exit(run(args, System.out, System.err, System::getenv));
    }

    /** Runs the tool and returns the exit code. {@code env} looks up environment variables. */
    static int run(String[] args, PrintStream out, PrintStream err, Function<String, String> env) {
        Options o;
        try {
            o = Options.parse(args);
        } catch (IllegalArgumentException e) {
            err.println("Error: " + e.getMessage());
            err.println("Run with --help for usage.");
            return USAGE;
        }
        if (o.help) {
            out.println(HELP);
            return OK;
        }

        MailConfig config;
        Email.Builder template;
        try {
            config = buildConfig(o, env);
            template = Email.builder().from(o.from).to(o.to).subject(o.subject);
        } catch (IllegalArgumentException | IllegalStateException e) {
            err.println("Error: " + e.getMessage());
            return USAGE;
        }

        out.println("Sending " + o.count + " email(s) to " + String.join(", ", o.to)
                + " via " + config.host() + ":" + config.port() + " (" + config.security() + ")"
                + (config.hasCredentials() ? " as " + config.username() : "") + " ...");

        long start = System.nanoTime();
        int sent = 0;
        Mailer mailer = new Mailer(config);
        try {
            for (int i = 1; i <= o.count; i++) {
                String subject = o.count > 1 ? o.subject + " (" + i + "/" + o.count + ")" : o.subject;
                mailer.send(template.subject(subject).text(body(o, config)).build());
                sent++;
            }
        } catch (MailException e) {
            err.println("FAILED after " + sent + " of " + o.count + " sent: " + e.getMessage());
            for (Throwable earlier : e.getSuppressed()) {
                err.println("  earlier attempt: " + earlier.getMessage());
            }
            return SEND_FAILED;
        } finally {
            mailer.close();
        }

        long millis = (System.nanoTime() - start) / 1_000_000;
        out.println("OK: sent " + sent + " email(s) over " + mailer.connectionsOpened()
                + " connection(s) in " + millis + " ms");
        return OK;
    }

    private static MailConfig buildConfig(Options o, Function<String, String> env) {
        MailConfig.Builder b = MailConfig.builder(o.host)
                .security(o.security)
                .maxAttempts(o.attempts);
        if (o.port > 0) {
            b.port(o.port);
        }
        if (o.user != null) {
            b.credentials(o.user, password(o.user, env));
        }
        String protocol = o.security == Security.TLS ? "smtps" : "smtp";
        if (o.trustAnyCert) {
            b.sslTrust("*").property("mail." + protocol + ".ssl.checkserveridentity", "false");
        }
        if (o.verbose) {
            b.debug(true);
        }
        return b.build();
    }

    private static String password(String user, Function<String, String> env) {
        String fromEnv = env.apply(PASSWORD_ENV);
        if (fromEnv != null) {
            return fromEnv;
        }
        Console console = System.console();
        if (console == null) {
            throw new IllegalArgumentException("--user given but no password: set " + PASSWORD_ENV);
        }
        char[] pw = console.readPassword("Password for %s: ", user);
        if (pw == null) {
            throw new IllegalArgumentException("no password entered");
        }
        return new String(pw);
    }

    private static String body(Options o, MailConfig config) {
        if (o.text != null) {
            return o.text;
        }
        String machine;
        try {
            machine = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            machine = "unknown";
        }
        return "This is a test email sent by mailroom." + System.lineSeparator()
                + System.lineSeparator()
                + "Server:  " + config.host() + ":" + config.port() + " (" + config.security() + ")" + System.lineSeparator()
                + "From:    " + machine + System.lineSeparator()
                + "Sent at: " + ZonedDateTime.now() + System.lineSeparator();
    }

    /** Parsed command-line options. */
    static final class Options {
        String host;
        int port;
        Security security = Security.STARTTLS;
        String user;
        String from;
        final List<String> to = new ArrayList<>();
        String subject = "mailroom test email";
        String text;
        int count = 1;
        int attempts = MailConfig.DEFAULT_MAX_ATTEMPTS;
        boolean trustAnyCert;
        boolean verbose;
        boolean help;

        static Options parse(String[] args) {
            Options o = new Options();
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                String value = null;
                int eq = arg.indexOf('=');
                if (arg.startsWith("--") && eq > 0) {
                    value = arg.substring(eq + 1);
                    arg = arg.substring(0, eq);
                }
                switch (arg) {
                    case "--help":
                    case "-h":
                        o.help = true;
                        return o;
                    case "--trust-any-cert":
                        o.trustAnyCert = true;
                        break;
                    case "--verbose":
                        o.verbose = true;
                        break;
                    case "--host":
                    case "--port":
                    case "--security":
                    case "--user":
                    case "--from":
                    case "--to":
                    case "--subject":
                    case "--text":
                    case "--count":
                    case "--attempts":
                        if (value == null) {
                            if (i + 1 >= args.length) {
                                throw new IllegalArgumentException(arg + " needs a value");
                            }
                            value = args[++i];
                        }
                        o.set(arg, value);
                        break;
                    default:
                        throw new IllegalArgumentException("unknown option " + arg);
                }
            }
            if (!o.help) {
                if (o.host == null) throw new IllegalArgumentException("--host is required");
                if (o.from == null) throw new IllegalArgumentException("--from is required");
                if (o.to.isEmpty()) throw new IllegalArgumentException("--to is required");
            }
            return o;
        }

        private void set(String name, String value) {
            switch (name) {
                case "--host": host = value; break;
                case "--port": port = number(name, value, 1, 65535); break;
                case "--security": security = security(value); break;
                case "--user": user = value; break;
                case "--from": from = value; break;
                case "--to":
                    for (String a : value.split(",")) {
                        if (!a.trim().isEmpty()) to.add(a.trim());
                    }
                    break;
                case "--subject": subject = value; break;
                case "--text": text = value; break;
                case "--count": count = number(name, value, 1, 10_000); break;
                case "--attempts": attempts = number(name, value, 1, 100); break;
                default: throw new IllegalArgumentException("unknown option " + name);
            }
        }

        private static Security security(String value) {
            try {
                return Security.valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("--security must be none, starttls or tls, not \"" + value + "\"");
            }
        }

        private static int number(String name, String value, int min, int max) {
            int n;
            try {
                n = Integer.parseInt(value.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(name + " must be a number, not \"" + value + "\"");
            }
            if (n < min || n > max) {
                throw new IllegalArgumentException(name + " must be between " + min + " and " + max);
            }
            return n;
        }
    }
}
