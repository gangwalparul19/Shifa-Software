package com.shifa.oms.gst.filing.dto;

import com.shifa.oms.gst.filing.domain.ReturnType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Request to reopen a filed return ({@code POST /api/ca/gst/filing/reopen}): FILED → PREPARED,
 * restricted to ADMIN/CA (GST returns &amp; filing, Reqs 2.4, 2.6, 2.7). The prior filing snapshot is
 * retained untouched (Req 2.5). The month is bounded 1–12 and the year to a sensible calendar range;
 * the return type is required.
 *
 * @param month      the calendar month, 1–12
 * @param year       the four-digit calendar year
 * @param returnType the return type to reopen (GSTR-1 or GSTR-3B)
 */
public record ReopenReturnRequest(
        @Min(value = 1, message = "month must be between 1 and 12")
        @Max(value = 12, message = "month must be between 1 and 12")
        int month,

        @Min(value = 2000, message = "year must be a valid four-digit year")
        @Max(value = 2100, message = "year must be a valid four-digit year")
        int year,

        @NotNull(message = "returnType is required")
        ReturnType returnType) {
}
