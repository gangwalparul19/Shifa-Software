package com.shifa.oms.courier;

/**
 * Raised when a courier API call fails or times out (Req 12.4). The outbox
 * drainer treats this as a retryable failure: the order retains {@code Packed}
 * and, once retries are exhausted, an admin failure notification is produced.
 */
public class CourierClientException extends RuntimeException {

    public CourierClientException(String message) {
        super(message);
    }

    public CourierClientException(String message, Throwable cause) {
        super(message, cause);
    }
}
