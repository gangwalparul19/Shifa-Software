package com.shifa.oms.common;

import org.springframework.http.HttpStatus;

import java.util.List;

/** Thrown when a request fails domain validation. Mapped to HTTP 400. */
public class ValidationException extends ApiException {

    private final List<String> details;

    public ValidationException(String message) {
        this(message, List.of());
    }

    public ValidationException(String message, List<String> details) {
        super(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message);
        this.details = details == null ? List.of() : List.copyOf(details);
    }

    public List<String> getDetails() {
        return details;
    }
}
