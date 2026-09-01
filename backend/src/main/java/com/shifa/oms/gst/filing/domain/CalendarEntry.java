package com.shifa.oms.gst.filing.domain;

import java.time.LocalDate;
import java.util.Objects;

/**
 * A single row of the filing calendar: one {@link ReturnType} for one {@link ReturnPeriod}, together
 * with its statutory due date, current {@link FilingStatus}, and the derived reminder / overdue /
 * due-today flags (GST returns &amp; filing, Reqs 4.1, 4.3, 4.4, 4.5).
 *
 * <p>Instances are produced by {@link FilingCalendarLogic#entry} so that the derived flags are always
 * consistent with the calendar arithmetic against a given {@code today}. The invariants a valid entry
 * upholds:
 * <ul>
 *   <li>a FILED return is never a {@code reminder}, {@code overdue}, or {@code dueToday};</li>
 *   <li>a return that is {@code dueToday} is surfaced as a {@code reminder} and is never
 *       {@code overdue} (Req 4.5);</li>
 *   <li>{@code overdueDays} is {@code 0} unless the entry is {@code overdue} (Req 4.4).</li>
 * </ul>
 *
 * <p>Pure and Spring-free; no JPA.
 *
 * @param returnType  the return type this row describes (GSTR-1 or GSTR-3B)
 * @param period      the reporting month this row describes
 * @param dueDate     the statutory portal due date (Req 4.1)
 * @param status      the current filing status of {@code returnType} for {@code period}
 * @param reminder    whether a due-date reminder should be surfaced (Req 4.3)
 * @param overdue     whether the return is overdue (not FILED and past its due date, Req 4.4)
 * @param overdueDays the count of whole days the return is overdue, {@code 0} when not overdue (Req 4.4)
 * @param dueToday    whether the return is due today (not FILED and due date equals today, Req 4.5)
 */
public record CalendarEntry(
        ReturnType returnType,
        ReturnPeriod period,
        LocalDate dueDate,
        FilingStatus status,
        boolean reminder,
        boolean overdue,
        int overdueDays,
        boolean dueToday) {

    /** Validates required references. */
    public CalendarEntry {
        Objects.requireNonNull(returnType, "returnType");
        Objects.requireNonNull(period, "period");
        Objects.requireNonNull(dueDate, "dueDate");
        Objects.requireNonNull(status, "status");
    }
}
