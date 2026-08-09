package com.shifa.oms.integration.meta;

/**
 * Raised when a Meta Graph API call fails (spec {@code meta-lead-sync}, Req 5.3, 5.4).
 *
 * <p>{@link #isRetryable()} decides whether the ingest drainer runs its backoff
 * ladder: transport errors, timeouts, HTTP 429 and 5xx are retryable; an
 * authorization failure (invalid/expired token) is <b>not</b> — retrying it would
 * fail identically and only delay the admin alert.
 */
public class MetaGraphException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final boolean retryable;

    public MetaGraphException(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
    }

    public MetaGraphException(String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
