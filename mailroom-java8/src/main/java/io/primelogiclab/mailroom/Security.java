package io.primelogiclab.mailroom;

/**
 * How the SMTP connection is secured.
 */
public enum Security {

    /** Plain SMTP, no encryption (typically port 25). */
    NONE,

    /** Connect in plain text, then upgrade with STARTTLS. The upgrade is required (typically port 587). */
    STARTTLS,

    /** Implicit TLS: the connection is encrypted from the start (SMTPS, typically port 465). */
    TLS
}
