package com.shifa.oms.integration;

/**
 * Thrown when an integration payload cannot be understood: a required field is
 * absent, unparseable, or outside its permitted range (Req 8.7, 8.9, 8.10).
 *
 * <p>Carries the offending {@link #getField() field name} because the whole point of
 * the {@code MALFORMED_PAYLOAD} outcome is that an admin can see *which* field broke
 * without reading the raw JSON in the health console.
 *
 * <p>Deliberately <b>not</b> retryable. A payload missing a required field will fail
 * identically on every attempt, so burning the retry ladder on it only delays the
 * admin alert.
 */
public class MalformedPayloadException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String field;

    public MalformedPayloadException(String field, String reason) {
        super(field + ": " + reason);
        this.field = field;
    }

    /** The payload field that could not be understood. */
    public String getField() {
        return field;
    }
}
