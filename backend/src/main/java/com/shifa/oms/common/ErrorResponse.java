package com.shifa.oms.common;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Standard JSON error envelope returned by every API endpoint on failure.
 *
 * <p>Example payload:
 * <pre>
 * {
 *   "timestamp": "2026-01-01T10:15:30+05:30",
 *   "status": 409,
 *   "error": "Conflict",
 *   "code": "DUPLICATE_SKU",
 *   "message": "A product with this SKU already exists.",
 *   "path": "/api/admin/products",
 *   "details": ["sku: must be unique"]
 * }
 * </pre>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
        OffsetDateTime timestamp,
        int status,
        String error,
        String code,
        String message,
        String path,
        List<String> details
) {

    public static ErrorResponse of(int status, String error, String code, String message, String path) {
        return new ErrorResponse(OffsetDateTime.now(), status, error, code, message, path, null);
    }

    public static ErrorResponse of(int status, String error, String code, String message, String path,
                                   List<String> details) {
        return new ErrorResponse(OffsetDateTime.now(), status, error, code, message, path, details);
    }
}
