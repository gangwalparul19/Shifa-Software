package com.shifa.oms.common;

import org.springframework.http.HttpStatus;

/**
 * Base type for domain/application exceptions that carry an HTTP status and a
 * stable machine-readable error {@code code} for the JSON error envelope.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
