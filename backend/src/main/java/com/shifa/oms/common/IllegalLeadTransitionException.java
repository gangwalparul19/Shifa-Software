package com.shifa.oms.common;

import org.springframework.http.HttpStatus;

/**
 * Thrown when a requested lead status transition is not permitted from the
 * lead's current status — an illegal edge or a change on a terminal lead
 * (Requirement 2.4, 2.7; design &sect;State Machine, &sect;Error Handling).
 * Mapped to HTTP 409; the lead's status is left unchanged.
 *
 * <p>Mirrors {@link com.shifa.oms.statemachine.IllegalStatusTransitionException}
 * in the {@code common} error-envelope style.
 */
public class IllegalLeadTransitionException extends ApiException {

    public IllegalLeadTransitionException(String message) {
        super(HttpStatus.CONFLICT, "ILLEGAL_LEAD_TRANSITION", message);
    }
}
