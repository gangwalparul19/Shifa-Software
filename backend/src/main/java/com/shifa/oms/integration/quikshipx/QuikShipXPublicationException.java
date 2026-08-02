package com.shifa.oms.integration.quikshipx;

/**
 * A failed QuikShipX publication attempt, raised so the surrounding transaction rolls
 * back and the order keeps {@code APPROVED} with no partial shipment row (Req 5.7).
 *
 * <p>Unchecked on purpose: the drainer catches {@link RuntimeException} to decide between
 * retrying and giving up, matching the courier drainer's contract, and a checked exception
 * here would not trigger Spring's default rollback rules.
 */
public class QuikShipXPublicationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final boolean retryable;

    public QuikShipXPublicationException(String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
    }

    /**
     * Whether the retry ladder should run. False for a permanent rejection, because a
     * body QuikShipX considers invalid will be just as invalid in eight minutes, and
     * retrying only delays the admin alert (Req 5.11).
     */
    public boolean isRetryable() {
        return retryable;
    }
}
