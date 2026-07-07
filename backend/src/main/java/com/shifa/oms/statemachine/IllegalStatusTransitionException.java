package com.shifa.oms.statemachine;

import com.shifa.oms.common.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Thrown when a requested order status transition is not permitted from the
 * current status. Mapped to HTTP 409; the order's status is left unchanged.
 *
 * <p>The transition table and enforcement logic are implemented in task 4.
 */
public class IllegalStatusTransitionException extends ApiException {

    public IllegalStatusTransitionException(String message) {
        super(HttpStatus.CONFLICT, "ILLEGAL_STATUS_TRANSITION", message);
    }
}
