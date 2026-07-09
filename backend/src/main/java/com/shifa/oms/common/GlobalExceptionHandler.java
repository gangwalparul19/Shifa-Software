package com.shifa.oms.common;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

/**
 * Global exception handler that renders every failure as the standard
 * {@link ErrorResponse} JSON envelope.
 *
 * <p>This is the skeleton wiring for task 1; individual modules add their own
 * {@link ApiException} subtypes, which are handled uniformly here.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Any domain/application exception carrying its own status and code. */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApiException(ApiException ex, HttpServletRequest request) {
        List<String> details = (ex instanceof ValidationException ve && !ve.getDetails().isEmpty())
                ? ve.getDetails()
                : null;
        ErrorResponse body = ErrorResponse.of(
                ex.getStatus().value(),
                ex.getStatus().getReasonPhrase(),
                ex.getCode(),
                ex.getMessage(),
                request.getRequestURI(),
                details);
        return ResponseEntity.status(ex.getStatus()).body(body);
    }

    /** Bean-validation (@Valid) failures on request bodies. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                      HttpServletRequest request) {
        List<String> details = ex.getBindingResult().getFieldErrors().stream()
                .map(this::formatFieldError)
                .toList();
        ErrorResponse body = ErrorResponse.of(
                HttpStatus.BAD_REQUEST.value(),
                HttpStatus.BAD_REQUEST.getReasonPhrase(),
                "VALIDATION_ERROR",
                "One or more fields are invalid.",
                request.getRequestURI(),
                details);
        return ResponseEntity.badRequest().body(body);
    }

    /**
     * A request parameter (or path variable) could not be converted to the
     * handler's expected type — e.g. an unknown {@code status} enum value or a
     * non-numeric id. This is a client error, so render a 400 rather than letting
     * it fall through to the 500 fallback.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex,
                                                            HttpServletRequest request) {
        String param = ex.getName();
        ErrorResponse body = ErrorResponse.of(
                HttpStatus.BAD_REQUEST.value(),
                HttpStatus.BAD_REQUEST.getReasonPhrase(),
                "INVALID_PARAMETER",
                "Invalid value for parameter '" + param + "'.",
                request.getRequestURI());
        return ResponseEntity.badRequest().body(body);
    }

    /**
     * An uploaded file (e.g. a payment screenshot) exceeded the configured
     * multipart size limit. Render a clear 413 instead of the generic 500 so the
     * user knows to attach a smaller image.
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleMaxUploadSize(MaxUploadSizeExceededException ex,
                                                             HttpServletRequest request) {
        ErrorResponse body = ErrorResponse.of(
                HttpStatus.PAYLOAD_TOO_LARGE.value(),
                HttpStatus.PAYLOAD_TOO_LARGE.getReasonPhrase(),
                "UPLOAD_TOO_LARGE",
                "The uploaded file is too large. Please attach a smaller screenshot.",
                request.getRequestURI());
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(body);
    }

    /** Authenticated user lacks the required authority. */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        ErrorResponse body = ErrorResponse.of(
                HttpStatus.FORBIDDEN.value(),
                HttpStatus.FORBIDDEN.getReasonPhrase(),
                "FORBIDDEN",
                "You are not authorized to perform this action.",
                request.getRequestURI());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
    }

    /** Unauthenticated access to a protected endpoint. */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ErrorResponse> handleAuthentication(AuthenticationException ex, HttpServletRequest request) {
        ErrorResponse body = ErrorResponse.of(
                HttpStatus.UNAUTHORIZED.value(),
                HttpStatus.UNAUTHORIZED.getReasonPhrase(),
                "UNAUTHENTICATED",
                "Authentication is required to access this resource.",
                request.getRequestURI());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(body);
    }

    /**
     * Client disconnected mid-response (e.g. a browser tab closed the admin SSE
     * stream at {@code /api/admin/events}). Spring's async machinery surfaces the
     * broken-pipe write as an {@link IOException} / {@link AsyncRequestNotUsableException}
     * on the async dispatch, but the response is already committed and there is
     * nothing to send back. Log at DEBUG and write nothing (returning {@code null}
     * from a {@code ResponseEntity} handler marks the request handled with no body),
     * so a normal client disconnect never spams the log with ERROR stack traces or
     * a "no converter for text/event-stream" failure.
     */
    @ExceptionHandler({AsyncRequestNotUsableException.class, IOException.class})
    public ResponseEntity<ErrorResponse> handleClientDisconnect(Exception ex, HttpServletRequest request) {
        if (ex instanceof AsyncRequestNotUsableException || isClientDisconnect(ex)) {
            log.debug("Client disconnected during {} {}: {}",
                    request.getMethod(), request.getRequestURI(), ex.getMessage());
            return null;
        }
        // A genuine, non-disconnect I/O error: fall back to the standard 500 envelope.
        return handleUnexpected(ex, request);
    }

    /**
     * Whether the throwable (or any of its causes) is a client-side connection
     * drop — broken pipe / connection reset / connection aborted — as opposed to a
     * real server-side I/O fault. Matches the Tomcat {@code ClientAbortException}
     * and the OS-level socket messages seen when an SSE/download client goes away.
     */
    private boolean isClientDisconnect(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            String type = t.getClass().getSimpleName();
            if ("ClientAbortException".equals(type)) {
                return true;
            }
            String message = t.getMessage();
            if (message != null) {
                String m = message.toLowerCase(Locale.ROOT);
                if (m.contains("broken pipe")
                        || m.contains("connection reset")
                        || m.contains("connection was aborted")
                        || m.contains("aborted by the software in your host machine")
                        || m.contains("an existing connection was forcibly closed")) {
                    return true;
                }
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    /** Fallback for any unhandled exception. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception for {} {}", request.getMethod(), request.getRequestURI(), ex);
        ErrorResponse body = ErrorResponse.of(
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                HttpStatus.INTERNAL_SERVER_ERROR.getReasonPhrase(),
                "INTERNAL_ERROR",
                "An unexpected error occurred.",
                request.getRequestURI());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    private String formatFieldError(FieldError fe) {
        return fe.getField() + ": " + fe.getDefaultMessage();
    }
}
