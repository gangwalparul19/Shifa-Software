package com.shifa.oms.auth;

import com.shifa.oms.common.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Thrown when a JWT is missing, malformed, has an invalid signature, is expired,
 * or is of the wrong {@link TokenType}. Mapped to HTTP 401 so the client knows to
 * (re-)authenticate.
 */
public class InvalidTokenException extends ApiException {

    public InvalidTokenException(String message) {
        super(HttpStatus.UNAUTHORIZED, "INVALID_TOKEN", message);
    }
}
