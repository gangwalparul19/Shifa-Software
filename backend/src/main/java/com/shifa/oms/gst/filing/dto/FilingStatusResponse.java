package com.shifa.oms.gst.filing.dto;

import com.shifa.oms.gst.filing.FilingStatusService.PeriodFilingStatus;
import com.shifa.oms.gst.filing.domain.FilingStatus;

/**
 * The current filing status of both return types for a selected period (GST returns &amp; filing,
 * Reqs 1.2, 1.6). Mirrors {@link PeriodFilingStatus} and carries the queried period so the UI can bind
 * status pills directly. Enums serialise as {@code name()}.
 *
 * @param month  the calendar month, 1–12
 * @param year   the four-digit calendar year
 * @param gstr1  the GSTR-1 filing status ({@link FilingStatus#NOT_STARTED} when never prepared)
 * @param gstr3b the GSTR-3B filing status ({@link FilingStatus#NOT_STARTED} when never prepared)
 */
public record FilingStatusResponse(int month, int year, FilingStatus gstr1, FilingStatus gstr3b) {

    /**
     * Maps the service's {@link PeriodFilingStatus} for a period to the API payload.
     *
     * @param status the pair of statuses for the period
     * @param month  the calendar month, 1–12
     * @param year   the four-digit calendar year
     * @return the response payload
     */
    public static FilingStatusResponse from(PeriodFilingStatus status, int month, int year) {
        return new FilingStatusResponse(month, year, status.gstr1(), status.gstr3b());
    }
}
