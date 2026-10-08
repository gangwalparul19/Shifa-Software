package com.shifa.oms.statemachine;

import com.shifa.oms.common.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Thrown when a transition is <em>legal</em> from the current status but the
 * acting role is not permitted to trigger it (design &sect;4.2).
 *
 * <p>Mapped to HTTP 403; the order's status is left unchanged. This is the
 * "not allowed for you" failure mode, distinct from
 * {@link IllegalStatusTransitionException} ("not allowed from here", HTTP 409).
 */
public class UnauthorizedTransitionException extends ApiException {

    public UnauthorizedTransitionException(String message) {
        super(HttpStatus.FORBIDDEN, "UNAUTHORIZED_TRANSITION", message);
    }
}
