package com.shifa.oms.gst.filing.dto;

import com.shifa.oms.gst.filing.domain.AmendmentTable;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request to resolve a manual-review amendment ({@code POST /api/ca/gst/amendments/{id}/review}),
 * restricted to ADMIN/CA (GST returns &amp; filing, Reqs 3.5, 3.7). The CA routes the held correction
 * into a chosen {@link AmendmentTable} and the open target period it lands in; the correction is then
 * moved to ROUTED (never discarded — Req 3.7). An optional note captures the review rationale.
 *
 * <p>The amendment table and target period are required because
 * {@code AmendmentService.review(id, table, targetYear, targetMonth)} routes the correction into a
 * concrete open period — a manual-review row carries no target until the CA supplies one.
 *
 * @param amendmentTable the amendment section the CA routes the correction into (required)
 * @param targetMonth    the open target period month (1–12) the amendment lands in (required)
 * @param targetYear     the open target period four-digit year the amendment lands in (required)
 * @param note           optional CA review note (≤ 1000 chars)
 */
public record AmendmentReviewRequest(
        @NotNull(message = "amendmentTable is required")
        AmendmentTable amendmentTable,

        @NotNull(message = "targetMonth is required")
        @Min(value = 1, message = "targetMonth must be between 1 and 12")
        @Max(value = 12, message = "targetMonth must be between 1 and 12")
        Integer targetMonth,

        @NotNull(message = "targetYear is required")
        @Min(value = 2000, message = "targetYear must be a valid four-digit year")
        @Max(value = 2100, message = "targetYear must be a valid four-digit year")
        Integer targetYear,

        @Size(max = 1000, message = "note must be at most 1000 characters")
        String note) {
}
