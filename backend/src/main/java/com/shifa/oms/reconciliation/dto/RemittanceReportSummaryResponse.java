package com.shifa.oms.reconciliation.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * KPI summary for the settlement report (V99): totals over the committed
 * remittance batches in a date window, plus the current COD still outstanding
 * from the courier. Powers the report page's KPI cards.
 *
 * @param from               inclusive window start (by import date)
 * @param to                 inclusive window end (by import date)
 * @param batchCount         number of committed imports in the window
 * @param ordersSettled      count of fully-settled order rows in the window
 * @param ordersPartiallySettled count of partially-settled (short-paid) rows in the window
 * @param ordersNeedingReview count of rows that need manual follow-up (mismatch / not-found / no-receivable / error)
 * @param totalRemitted      sum of remitted amounts in the window
 * @param totalSettled       sum of amounts actually settled (full + partial) in the window
 * @param totalShortfall     sum of shortfalls on partial settlements in the window
 * @param codStillOutstanding current unsettled courier COD across all time (what is still to be remitted)
 */
public record RemittanceReportSummaryResponse(
        LocalDate from,
        LocalDate to,
        long batchCount,
        long ordersSettled,
        long ordersPartiallySettled,
        long ordersNeedingReview,
        BigDecimal totalRemitted,
        BigDecimal totalSettled,
        BigDecimal totalShortfall,
        BigDecimal codStillOutstanding
) {
}
