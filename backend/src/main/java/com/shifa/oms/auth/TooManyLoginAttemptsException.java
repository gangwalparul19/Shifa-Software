package com.shifa.oms.auth;

import com.shifa.oms.common.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Thrown when a login key ({@code username|clientIp}) has exceeded the allowed
 * number of failed attempts and is temporarily locked out. Rendered as HTTP 429.
 */
public class TooManyLoginAttemptsException extends ApiException {

    public TooManyLoginAttemptsException(String message) {
        super(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_ATTEMPTS", message);
    }
}
