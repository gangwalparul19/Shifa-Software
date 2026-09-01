package com.shifa.oms.gst.filing.dto;

import com.shifa.oms.gst.filing.domain.ReturnType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Request to mark a return prepared ({@code POST /api/ca/gst/filing/prepare}): NOT_STARTED → PREPARED
 * (GST returns &amp; filing, Reqs 1.3, 1.7). The month is bounded 1–12 and the year to a sensible
 * calendar range; the return type is required. Enums bind from {@code name()}.
 *
 * @param month      the calendar month, 1–12
 * @param year       the four-digit calendar year
 * @param returnType the return type to prepare (GSTR-1 or GSTR-3B)
 */
public record PrepareReturnRequest(
        @Min(value = 1, message = "month must be between 1 and 12")
        @Max(value = 12, message = "month must be between 1 and 12")
        int month,

        @Min(value = 2000, message = "year must be a valid four-digit year")
        @Max(value = 2100, message = "year must be a valid four-digit year")
        int year,

        @NotNull(message = "returnType is required")
        ReturnType returnType) {
}
