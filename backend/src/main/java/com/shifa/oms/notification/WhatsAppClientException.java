package com.shifa.oms.notification;

/**
 * Raised when a WhatsApp send fails or times out (Req 14.4).
 *
 * <p>Thrown by a {@link WhatsAppClient} implementation; the WhatsApp outbox
 * drainer catches it, records the error on the event, retries with backoff, and
 * — once retries are exhausted — marks the event {@code FAILED} and flags the
 * order for admin review.
 */
public class WhatsAppClientException extends RuntimeException {

    public WhatsAppClientException(String message) {
        super(message);
    }

    public WhatsAppClientException(String message, Throwable cause) {
        super(message, cause);
    }
}
