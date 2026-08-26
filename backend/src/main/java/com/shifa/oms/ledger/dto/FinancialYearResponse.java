package com.shifa.oms.ledger.dto;

import com.shifa.oms.ledger.FinancialYear;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Read view of an Indian financial year ({@code GET /api/accounting/financial-years}, Req 4).
 *
 * <p>A financial year runs 1 April – 31 March. {@code closed} marks a year that rejects posting
 * (Req 4.3); {@code closedAt}/{@code closedBy} snapshot when and by whom it was closed.
 *
 * @param id        the financial year id
 * @param startDate the FY start (1 April)
 * @param endDate   the FY end (31 March)
 * @param label     the human-readable label (e.g. {@code "2025-26"})
 * @param closed    whether the year is closed to posting
 * @param closedAt  when the year was closed, or {@code null}
 * @param closedBy  who closed the year, or {@code null}
 */
public record FinancialYearResponse(
        Long id,
        LocalDate startDate,
        LocalDate endDate,
        String label,
        boolean closed,
        LocalDateTime closedAt,
        String closedBy
) {

    /** Maps a persisted {@link FinancialYear} to its response view. */
    public static FinancialYearResponse from(FinancialYear fy) {
        return new FinancialYearResponse(
                fy.getId(),
                fy.getStartDate(),
                fy.getEndDate(),
                fy.getLabel(),
                fy.isClosed(),
                fy.getClosedAt(),
                fy.getClosedBy());
    }
}
