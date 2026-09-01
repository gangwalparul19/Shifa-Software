package com.shifa.oms.quikshipx;

/**
 * Raised when a QuikShipX API call fails, times out, or is rejected. Carries a
 * {@link #retryable} flag so the create/confirm drainer and the courier
 * assignment path know whether trying again could help: transport errors,
 * timeouts, 408/429/5xx and "not ready yet" soft failures are retryable; a body
 * QuikShipX considers invalid (bad address/HSN) is permanent.
 */
public class QuikShipXException extends RuntimeException {

    private final boolean retryable;

    public QuikShipXException(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
    }

    public QuikShipXException(String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
