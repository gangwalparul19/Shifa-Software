package com.shifa.oms.chatbot.domain;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * The date window a chatbot question refers to, resolved in IST by
 * {@link PeriodParser}. {@code from}/{@code to} are inclusive calendar days;
 * {@code null} on both means "all time".
 *
 * @param phrase text that reads naturally after a figure ("today", "this month",
 *               "on 25 Oct 2026", "overall")
 * @param detail the concrete dates behind the phrase, or {@code null} when the
 *               phrase already says it all
 */
public record Period(String phrase, String detail, LocalDate from, LocalDate to) {

    private static final LocalDateTime MIN = LocalDateTime.of(1970, 1, 1, 0, 0);
    private static final LocalDateTime MAX = LocalDateTime.of(9999, 12, 31, 23, 59, 59);

    /** The unbounded window used when the question names no period. */
    public static Period allTime() {
        return new Period("overall", "all time", null, null);
    }

    public boolean isAllTime() {
        return from == null && to == null;
    }

    /** Inclusive start instant (IST wall clock, as stored in {@code orders.created_at}). */
    public LocalDateTime start() {
        return from == null ? MIN : from.atStartOfDay();
    }

    /** Exclusive end instant. */
    public LocalDateTime endExclusive() {
        return to == null ? MAX : to.plusDays(1).atStartOfDay();
    }

    /** "today (31 Oct 2026)" / "overall (all time)" / "on 25 Oct 2026". */
    public String display() {
        return detail == null ? phrase : phrase + " (" + detail + ")";
    }
}
