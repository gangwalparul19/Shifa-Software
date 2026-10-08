package com.shifa.oms.gst.filing.dto;

import com.shifa.oms.gst.filing.domain.CalendarEntry;
import com.shifa.oms.gst.filing.domain.FilingStatus;
import com.shifa.oms.gst.filing.domain.ReturnType;

import java.time.LocalDate;

/**
 * One row of the filing calendar for the API (GST returns &amp; filing, Reqs 4.1, 4.3, 4.4, 4.5):
 * a single {@link ReturnType} for one reporting month, its statutory due date, current
 * {@link FilingStatus}, and the derived reminder / overdue / due-today flags. Mirrors the pure
 * {@link CalendarEntry} one-to-one so the Angular model binds directly; enums serialise as
 * {@code name()} and {@link #dueDate} as {@code yyyy-MM-dd}.
 *
 * @param returnType  the return type this row describes (GSTR-1 or GSTR-3B)
 * @param month       the reporting month, 1–12
 * @param year        the reporting year (four-digit)
 * @param dueDate     the statutory portal due date (Req 4.1)
 * @param status      the current filing status for the period and return type
 * @param reminder    whether a due-date reminder should be surfaced (Req 4.3)
 * @param overdue     whether the return is overdue (not FILED and past its due date, Req 4.4)
 * @param overdueDays the count of whole days overdue, {@code 0} when not overdue (Req 4.4)
 * @param dueToday    whether the return is due today (not FILED and due date equals today, Req 4.5)
 */
public record CalendarEntryResponse(
        ReturnType returnType,
        int month,
        int year,
        LocalDate dueDate,
        FilingStatus status,
        boolean reminder,
        boolean overdue,
        int overdueDays,
        boolean dueToday) {

    /**
     * Maps a pure {@link CalendarEntry} to the API payload (identical figures and flags).
     *
     * @param entry the calendar entry produced by {@code FilingCalendarLogic}
     * @return the response payload
     */
    public static CalendarEntryResponse from(CalendarEntry entry) {
        return new CalendarEntryResponse(
                entry.returnType(),
                entry.period().month(),
                entry.period().year(),
                entry.dueDate(),
                entry.status(),
                entry.reminder(),
                entry.overdue(),
                entry.overdueDays(),
                entry.dueToday());
    }
}
