package com.shifa.oms.gst.filing.dto;

import com.shifa.oms.gst.filing.domain.CalendarEntry;

import java.util.List;

/**
 * The filing calendar for an Indian financial year (GST returns &amp; filing, Reqs 4.1–4.6): the
 * FY start year plus one {@link CalendarEntryResponse} per month × return type, in the order the
 * calendar service produces them (April first, GSTR-1 before GSTR-3B within each month).
 *
 * @param financialYearStartYear the calendar year in which the FY starts (e.g. {@code 2025} for
 *                               FY 2025-26)
 * @param entries                the calendar rows, each presenting a due date, status, and flags
 */
public record CalendarResponse(int financialYearStartYear, List<CalendarEntryResponse> entries) {

    /**
     * Maps the calendar service's list of pure {@link CalendarEntry}s to the API payload.
     *
     * @param financialYearStartYear the FY start year the calendar was built for
     * @param calendar                the calendar entries in presentation order
     * @return the response payload
     */
    public static CalendarResponse from(int financialYearStartYear, List<CalendarEntry> calendar) {
        return new CalendarResponse(
                financialYearStartYear,
                calendar.stream().map(CalendarEntryResponse::from).toList());
    }
}
