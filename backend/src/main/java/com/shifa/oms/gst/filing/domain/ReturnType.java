package com.shifa.oms.gst.filing.domain;

/**
 * A GST return type filed monthly, each with its statutory portal due day
 * (GST returns &amp; filing, Req 4.1).
 *
 * <ul>
 *   <li>{@link #GSTR1} — outward-supplies return; due on the <strong>11th</strong> of the month
 *       following the {@link ReturnPeriod}.</li>
 *   <li>{@link #GSTR3B} — summary return; due on the <strong>20th</strong> of the month following
 *       the {@link ReturnPeriod}.</li>
 * </ul>
 *
 * <p>The {@link #dueDay()} is the day-of-month within the <em>following</em> month on which the
 * return is due; {@link ReturnPeriod#dueDate(ReturnType)} combines it with the period to produce the
 * concrete due date. Pure and Spring-free.
 */
public enum ReturnType {

    /** Outward-supplies return, due on the 11th of the following month. */
    GSTR1(11),

    /** Summary return, due on the 20th of the following month. */
    GSTR3B(20);

    private final int dueDay;

    ReturnType(int dueDay) {
        this.dueDay = dueDay;
    }

    /**
     * @return the day-of-month (within the month following the {@link ReturnPeriod}) on which this
     *         return type is due — {@code 11} for GSTR-1 and {@code 20} for GSTR-3B.
     */
    public int dueDay() {
        return dueDay;
    }
}
