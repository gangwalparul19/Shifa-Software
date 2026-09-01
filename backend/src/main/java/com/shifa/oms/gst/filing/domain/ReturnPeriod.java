package com.shifa.oms.gst.filing.domain;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Locale;

/**
 * A single GST reporting month, identified by a calendar {@code month} (1–12) and its four-digit
 * calendar {@code year} (GST returns &amp; filing, Req 1.1, A2).
 *
 * <p>The period is validated on construction ({@code 1 ≤ month ≤ 12}) and derives:
 * <ul>
 *   <li>the statutory {@link #dueDate(ReturnType) due date} for a return type — the
 *       {@link ReturnType#dueDay() due day} of the <strong>following</strong> month (Req 4.1);</li>
 *   <li>portal-compatible formatting helpers — {@link #portalFp() MMYYYY}, {@link #twoDigitMonth()},
 *       and the Indian {@link #financialYearStartYear() financial-year} helpers used by the
 *       GSTR-1 export (Req 6.1).</li>
 * </ul>
 *
 * <p>Pure and Spring-free; no JPA. Money is not modelled here (this is a calendar value object).
 *
 * @param month the calendar month, 1 (January) through 12 (December)
 * @param year  the four-digit calendar year of {@code month}
 */
public record ReturnPeriod(int month, int year) {

    /**
     * Validates the calendar month range (Req 1.1).
     *
     * @throws IllegalArgumentException when {@code month} is not in {@code [1, 12]}
     */
    public ReturnPeriod {
        if (month < 1 || month > 12) {
            throw new IllegalArgumentException("month must be between 1 and 12, was " + month);
        }
    }

    /** @return this period as a {@link YearMonth}. */
    public YearMonth yearMonth() {
        return YearMonth.of(year, month);
    }

    /**
     * The statutory portal due date for a return type: the {@link ReturnType#dueDay() due day} of the
     * month <strong>following</strong> this period (Req 4.1). For example, GSTR-1 for period
     * {@code (3, 2026)} is due {@code 2026-04-11}.
     *
     * @param returnType the return type whose due day applies
     * @return the concrete due date in the following month
     */
    public LocalDate dueDate(ReturnType returnType) {
        return yearMonth().plusMonths(1).atDay(returnType.dueDay());
    }

    /**
     * @return the portal period token {@code fp} as {@code MMYYYY} (two-digit month + four-digit
     *         calendar year), e.g. {@code (3, 2026)} → {@code "032026"} (Req 6.1).
     */
    public String portalFp() {
        return String.format(Locale.ROOT, "%02d%04d", month, year);
    }

    /**
     * @return the zero-padded two-digit month, {@code "01"} through {@code "12"} (Req 6.1).
     */
    public String twoDigitMonth() {
        return String.format(Locale.ROOT, "%02d", month);
    }

    /**
     * The starting calendar year of the Indian financial year (April–March) that contains this
     * period: months April–December belong to the FY starting that calendar year, while
     * January–March belong to the FY starting the previous calendar year. For example, March 2026
     * belongs to FY starting 2025 ("2025-26"), and April 2026 to FY starting 2026 ("2026-27").
     *
     * @return the FY start year
     */
    public int financialYearStartYear() {
        return month >= 4 ? year : year - 1;
    }

    /**
     * @return the Indian financial-year label for this period, e.g. {@code "2025-26"} (Req 6.1).
     */
    public String financialYearLabel() {
        int start = financialYearStartYear();
        return String.format(Locale.ROOT, "%04d-%02d", start, (start + 1) % 100);
    }
}
