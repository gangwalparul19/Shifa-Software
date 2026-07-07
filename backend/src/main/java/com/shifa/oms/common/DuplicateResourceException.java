package com.shifa.oms.common;

import org.springframework.http.HttpStatus;

/**
 * Thrown when a uniqueness constraint would be violated (e.g. duplicate SKU or
 * username). Mapped to HTTP 409.
 */
public class DuplicateResourceException extends ApiException {

    public DuplicateResourceException(String code, String message) {
        super(HttpStatus.CONFLICT, code, message);
    }
}
