package com.shifa.oms.insights.domain;

/**
 * The severity of an {@link Insight} (design &sect;Pure domain; Req 10.1, 10.2),
 * driving both the dashboard colouring and whether the insight is pushed to
 * admins as a notification.
 *
 * <ul>
 *   <li>{@link #INFO} — informational; never notified.</li>
 *   <li>{@link #WARNING} — needs attention; notified.</li>
 *   <li>{@link #DANGER} — urgent; notified.</li>
 * </ul>
 *
 * <p>To keep this domain pure (no persistence/web imports) the notification
 * severity strings are hard-coded here, but they intentionally match the
 * {@code com.shifa.oms.adminnotification.AdminNotification.SEVERITY_*} constants
 * ({@code INFO→"info"}, {@code WARNING→"warning"}, {@code DANGER→"danger"}) so a
 * notifiable insight maps straight onto an admin notification.
 */
public enum InsightSeverity {
    INFO,
    WARNING,
    DANGER;

    /**
     * The matching {@code AdminNotification.SEVERITY_*} string for this severity
     * (never null): {@code INFO→"info"}, {@code WARNING→"warning"},
     * {@code DANGER→"danger"}.
     */
    public String toNotificationSeverity() {
        return switch (this) {
            case INFO -> "info";
            case WARNING -> "warning";
            case DANGER -> "danger";
        };
    }

    /**
     * Whether an insight of this severity is surfaced to admins as a notification
     * (Req 10.1, 10.2): {@code true} only for {@link #WARNING} and {@link #DANGER}.
     */
    public boolean isNotifiable() {
        return this == WARNING || this == DANGER;
    }
}
