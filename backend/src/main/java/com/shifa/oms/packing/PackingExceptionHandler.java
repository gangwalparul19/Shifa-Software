package com.shifa.oms.packing;

import com.shifa.oms.common.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

/**
 * Packing-specific error rendering.
 *
 * <p>Renders {@link OrderNotPackableException} (Req 11.4) as the standard
 * {@link ErrorResponse} envelope but adds the order's current status to
 * {@code details}, so the packing UI can show a precise "cannot pack — order is
 * &lt;status&gt;" message from structured data rather than parsing the message
 * string. Being a more specific handler than the generic {@code ApiException}
 * advice, Spring routes this exception here.
 */
@RestControllerAdvice
public class PackingExceptionHandler {

    @ExceptionHandler(OrderNotPackableException.class)
    public ResponseEntity<ErrorResponse> handleNotPackable(OrderNotPackableException ex,
                                                           HttpServletRequest request) {
        ErrorResponse body = ErrorResponse.of(
                ex.getStatus().value(),
                ex.getStatus().getReasonPhrase(),
                ex.getCode(),
                ex.getMessage(),
                request.getRequestURI(),
                List.of("currentStatus: " + ex.getCurrentStatus()));
        return ResponseEntity.status(ex.getStatus()).body(body);
    }

    @ExceptionHandler(OrderNotHandoverableException.class)
    public ResponseEntity<ErrorResponse> handleNotHandoverable(OrderNotHandoverableException ex,
                                                               HttpServletRequest request) {
        ErrorResponse body = ErrorResponse.of(
                ex.getStatus().value(),
                ex.getStatus().getReasonPhrase(),
                ex.getCode(),
                ex.getMessage(),
                request.getRequestURI(),
                List.of("currentStatus: " + ex.getCurrentStatus()));
        return ResponseEntity.status(ex.getStatus()).body(body);
    }

    @ExceptionHandler(OrderNotDispatchableException.class)
    public ResponseEntity<ErrorResponse> handleNotDispatchable(OrderNotDispatchableException ex,
                                                               HttpServletRequest request) {
        ErrorResponse body = ErrorResponse.of(
                ex.getStatus().value(),
                ex.getStatus().getReasonPhrase(),
                ex.getCode(),
                ex.getMessage(),
                request.getRequestURI(),
                List.of("currentStatus: " + ex.getCurrentStatus()));
        return ResponseEntity.status(ex.getStatus()).body(body);
    }
}
