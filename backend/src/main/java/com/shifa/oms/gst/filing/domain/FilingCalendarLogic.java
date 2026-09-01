package com.shifa.oms.gst.filing.domain;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * The pure calendar math behind the filing calendar (GST returns &amp; filing, Reqs 4.1, 4.3, 4.4,
 * 4.5).
 *
 * <p>Given {@code today} (resolved by the caller from an injected {@code Clock}), a
 * {@link ReturnPeriod} and {@link ReturnType}, its current {@link FilingStatus}, and the configured
 * {@link ReminderWindow}, {@link #entry} derives the statutory due date and the reminder / overdue /
 * due-today flags into a {@link CalendarEntry}. The rules:
 * <ul>
 *   <li><strong>due date</strong> — the {@link ReturnPeriod#dueDate(ReturnType) statutory due date}
 *       (Req 4.1);</li>
 *   <li><strong>reminder</strong> — surfaced when the return is not FILED and the whole days from
 *       {@code today} to the due date are within {@code [0, window]} (Req 4.3);</li>
 *   <li><strong>due today</strong> — when the return is not FILED and its due date equals
 *       {@code today}: it is surfaced as a reminder and is <em>never</em> overdue (Req 4.5);</li>
 *   <li><strong>overdue</strong> — when the return is not FILED and its due date is strictly before
 *       {@code today}, with {@code overdueDays} counting whole days from the due date (exclusive) to
 *       {@code today} (inclusive) (Req 4.4).</li>
 * </ul>
 *
 * <p>A FILED return is neither a reminder, overdue, nor due today. The class is stateless,
 * Spring-free and JPA-free, so it is trivially unit- and property-testable.
 */
public final class FilingCalendarLogic {

    private FilingCalendarLogic() {
    }

    /**
     * Builds the {@link CalendarEntry} for a return type and period against a given {@code today}
     * (Reqs 4.1, 4.3, 4.4, 4.5).
     *
     * @param today      the current date (resolved from an injected {@code Clock})
     * @param period     the reporting month
     * @param returnType the return type whose due day applies
     * @param status     the current filing status of {@code returnType} for {@code period}
     * @param window     the configured reminder look-ahead window
     * @return a fully-derived calendar entry whose flags are consistent with the arithmetic
     */
    public static CalendarEntry entry(
            LocalDate today,
            ReturnPeriod period,
            ReturnType returnType,
            FilingStatus status,
            ReminderWindow window) {
        Objects.requireNonNull(today, "today");
        Objects.requireNonNull(period, "period");
        Objects.requireNonNull(returnType, "returnType");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(window, "window");

        LocalDate dueDate = period.dueDate(returnType);
        boolean notFiled = status != FilingStatus.FILED;

        long daysUntilDue = ChronoUnit.DAYS.between(today, dueDate);
        boolean dueToday = notFiled && dueDate.isEqual(today);
        boolean reminder = notFiled && daysUntilDue >= 0 && daysUntilDue <= window.days();
        boolean overdue = notFiled && dueDate.isBefore(today);
        int overdueDays = overdue ? (int) ChronoUnit.DAYS.between(dueDate, today) : 0;

        return new CalendarEntry(returnType, period, dueDate, status, reminder, overdue, overdueDays, dueToday);
    }
}
