package com.shifa.oms.analytics.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Demand & cash forecast (FEATURE-ROADMAP §6.5): a simple moving-average
 * projection of per-product demand plus an expected-COD-collection outlook.
 * Distinct from the LOW_STOCK_REORDER insight (which flags reorder alerts) — this
 * is a forward projection of units and cash.
 *
 * @param lookbackDays the trailing window used to compute the run-rate
 * @param horizonDays  the forward projection horizon
 * @param topDemand    products by projected demand over the horizon (highest first)
 * @param cash         the cash-collection outlook
 */
public record ForecastReport(
        int lookbackDays,
        int horizonDays,
        List<ProductForecastRow> topDemand,
        CashForecast cash
) {

    /**
     * A product's recent demand and projected demand over the horizon.
     *
     * @param productId      the product
     * @param productName    the product name
     * @param unitsRecent    units sold in the lookback window
     * @param avgPerDay      average units/day over the lookback window
     * @param projectedUnits projected units over the horizon (avgPerDay × horizon)
     */
    public record ProductForecastRow(
            Long productId,
            String productName,
            long unitsRecent,
            double avgPerDay,
            long projectedUnits) {
    }

    /**
     * Expected COD collection outlook.
     *
     * @param outstandingCod        COD still expected across active orders
     * @param windowDays            the recent window used for the run-rate
     * @param collectedInWindow     COD collected within that window
     * @param avgWeeklyCollection   average weekly COD collected (run-rate)
     * @param estimatedWeeksToClear weeks to clear the outstanding at the current run-rate (null when no run-rate)
     */
    public record CashForecast(
            BigDecimal outstandingCod,
            int windowDays,
            BigDecimal collectedInWindow,
            BigDecimal avgWeeklyCollection,
            Double estimatedWeeksToClear) {
    }
}
