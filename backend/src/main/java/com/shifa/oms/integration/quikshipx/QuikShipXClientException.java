package com.shifa.oms.integration.quikshipx;

import java.util.List;

/**
 * A failed QuikShipX call.
 *
 * <p>{@link #isRetryable()} is the important part. A transport error or a 5xx should
 * burn the retry ladder; a 4xx that names a bad field will fail identically forever, so
 * retrying it just delays the admin alert by eight minutes (Req 5.6, 5.11).
 *
 * <p>{@link #getRejectedFields()} carries the field names QuikShipX complained about,
 * so the health console can tell an admin what to fix instead of showing a raw body.
 */
public class QuikShipXClientException extends Exception {

    private static final long serialVersionUID = 1L;

    private final boolean retryable;
    private final Integer httpStatus;
    private final List<String> rejectedFields;
    private final boolean alreadyExists;

    public QuikShipXClientException(String message, boolean retryable) {
        this(message, retryable, null, List.of(), null);
    }

    public QuikShipXClientException(String message, boolean retryable, Throwable cause) {
        this(message, retryable, null, List.of(), cause);
    }

    public QuikShipXClientException(String message, boolean retryable, Integer httpStatus,
                                    List<String> rejectedFields, Throwable cause) {
        this(message, retryable, httpStatus, rejectedFields, cause, false);
    }

    public QuikShipXClientException(String message, boolean retryable, Integer httpStatus,
                                    List<String> rejectedFields, Throwable cause,
                                    boolean alreadyExists) {
        super(message, cause);
        this.retryable = retryable;
        this.httpStatus = httpStatus;
        this.rejectedFields = rejectedFields == null ? List.of() : List.copyOf(rejectedFields);
        this.alreadyExists = alreadyExists;
    }

    /**
     * Whether QuikShipX rejected the call because the {@code customer_order_id} already
     * exists on their side. This is not a real failure: it means the order is already
     * booked (a concurrent auto-publish won the race, or the order was sent before), so the
     * publisher treats it as an idempotent "already published" rather than an error.
     */
    public boolean isAlreadyExists() {
        return alreadyExists;
    }

    /** Whether another attempt could plausibly succeed. */
    public boolean isRetryable() {
        return retryable;
    }

    /** The HTTP status, when the failure was a response rather than a transport error. */
    public Integer getHttpStatus() {
        return httpStatus;
    }

    /** Field names QuikShipX rejected, for an admin-facing failure reason. */
    public List<String> getRejectedFields() {
        return rejectedFields;
    }
}
