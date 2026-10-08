package com.shifa.oms.gst.filing.domain;

/**
 * The filing lifecycle state of a {@link ReturnType} for a {@link ReturnPeriod}
 * (GST returns &amp; filing, Req 1.1).
 *
 * <ul>
 *   <li>{@link #NOT_STARTED} — the implicit status when no filing record exists for the period and
 *       return type (Req 1.2); nothing has been prepared yet.</li>
 *   <li>{@link #PREPARED} — the return has been assembled and marked ready to file, but is not yet
 *       filed (Req 1.3).</li>
 *   <li>{@link #FILED} — the return has been filed; its figures are locked and served from an
 *       immutable snapshot (Reqs 1.4, 2.1).</li>
 * </ul>
 *
 * <p>Pure and Spring-free; the legal transitions between these states are encoded by
 * {@code FilingStatusMachine}.
 */
public enum FilingStatus {
    NOT_STARTED,
    PREPARED,
    FILED
}
