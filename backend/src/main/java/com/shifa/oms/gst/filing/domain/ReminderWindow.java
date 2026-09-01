package com.shifa.oms.gst.filing.domain;

/**
 * The configurable look-ahead window, in whole days, used by the filing calendar to decide when a
 * not-yet-filed return should surface a due-date reminder (GST returns &amp; filing, Req 4.6).
 *
 * <p>The window is constrained to {@code [1, 30]} days. The {@link #of(Integer)} factory resolves a
 * configured value (e.g. {@code app_settings.gst_reminder_window_days}) to a valid window,
 * substituting the {@link #DEFAULT_DAYS default of 7} whenever the configured value is {@code null}
 * or falls outside the supported range. A {@code ReminderWindow} therefore always holds a value in
 * {@code [1, 30]}.
 *
 * <p>Pure and Spring-free; no JPA.
 *
 * @param days the reminder look-ahead in whole days, always within {@code [1, 30]}
 */
public record ReminderWindow(int days) {

    /** The smallest supported reminder window, in days. */
    public static final int MIN_DAYS = 1;

    /** The largest supported reminder window, in days. */
    public static final int MAX_DAYS = 30;

    /** The default reminder window applied when no valid value is configured (Req 4.6). */
    public static final int DEFAULT_DAYS = 7;

    /**
     * Validates the range invariant (Req 4.6).
     *
     * @throws IllegalArgumentException when {@code days} is not in {@code [1, 30]}
     */
    public ReminderWindow {
        if (days < MIN_DAYS || days > MAX_DAYS) {
            throw new IllegalArgumentException(
                    "reminder window days must be between " + MIN_DAYS + " and " + MAX_DAYS + ", was " + days);
        }
    }

    /**
     * Resolves a configured reminder window to a valid one, defaulting to {@link #DEFAULT_DAYS 7}
     * when the configured value is {@code null} or outside {@code [1, 30]} (Req 4.6).
     *
     * @param configured the configured value, or {@code null} when unset
     * @return a valid {@code ReminderWindow} — the configured value when in range, otherwise the default
     */
    public static ReminderWindow of(Integer configured) {
        if (configured == null || configured < MIN_DAYS || configured > MAX_DAYS) {
            return new ReminderWindow(DEFAULT_DAYS);
        }
        return new ReminderWindow(configured);
    }
}
