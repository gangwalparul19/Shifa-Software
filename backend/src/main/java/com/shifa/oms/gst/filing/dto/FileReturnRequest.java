package com.shifa.oms.gst.filing.dto;

import com.shifa.oms.gst.filing.domain.ReturnType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request to file a prepared return ({@code POST /api/ca/gst/filing/file}): PREPARED → FILED
 * (GST returns &amp; filing, Reqs 1.4, 1.5, 1.8, 2.3, 5.1). An optional portal acknowledgement
 * reference is bounded to 50 characters when supplied (Req 1.5). The month is bounded 1–12 and the
 * year to a sensible calendar range; the return type is required.
 *
 * @param month        the calendar month, 1–12
 * @param year         the four-digit calendar year
 * @param returnType   the return type to file (GSTR-1 or GSTR-3B)
 * @param ackReference optional portal acknowledgement reference (≤ 50 chars, nullable/blank = none)
 */
public record FileReturnRequest(
        @Min(value = 1, message = "month must be between 1 and 12")
        @Max(value = 12, message = "month must be between 1 and 12")
        int month,

        @Min(value = 2000, message = "year must be a valid four-digit year")
        @Max(value = 2100, message = "year must be a valid four-digit year")
        int year,

        @NotNull(message = "returnType is required")
        ReturnType returnType,

        @Size(max = 50, message = "ackReference must be at most 50 characters")
        String ackReference) {
}
