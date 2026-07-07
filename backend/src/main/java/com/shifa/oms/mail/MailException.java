package com.shifa.oms.mail;

/**
 * Raised when an email send fails (Feature E3).
 *
 * <p>Thrown by a {@link MailService} implementation; the mock backend uses it to
 * simulate failures for tests, and {@link SmtpMailService} wraps Spring's mail
 * exceptions in it so callers depend only on this module's contract.
 */
public class MailException extends RuntimeException {

    public MailException(String message) {
        super(message);
    }

    public MailException(String message, Throwable cause) {
        super(message, cause);
    }
}
