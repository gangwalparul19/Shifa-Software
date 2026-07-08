package com.shifa.oms.insights.domain;

import java.math.BigDecimal;

/**
 * The tunable thresholds the pure {@code InsightEngine} scores against (design
 * &sect;Pure domain, &sect;Config). Built from {@code app.insights.*} config by
 * the computation service; {@link #defaults()} gives the design defaults for
 * tests and for a missing config.
 *
 * @param salesAnomalyPct    sales deviation (percent) that flags a SALES_ANOMALY (default 30)
 * @param reorderLookbackDays consumption lookback window in days (default 30)
 * @param reorderCoverDays    days-of-cover threshold below which a reorder is flagged (default 14)
 * @param rtoRiskThreshold    RTO risk score (0–100) at or above which an order is flagged (default 60)
 * @param courierRtoWarnPct   courier RTO percent above which a scorecard is WARNING (default 15)
 * @param returnRateWarnPct   return-rate percent above which a RETURN_RATE_ANOMALY fires (default 10)
 * @param codOutstandingWarn  unsettled-COD amount above which a COD build-up fires (default 50000)
 */
public record InsightThresholds(
        BigDecimal salesAnomalyPct,
        int reorderLookbackDays,
        int reorderCoverDays,
        int rtoRiskThreshold,
        BigDecimal courierRtoWarnPct,
        BigDecimal returnRateWarnPct,
        BigDecimal codOutstandingWarn) {

    /** The design default thresholds (30 / 30 / 14 / 60 / 15 / 10 / 50000). */
    public static InsightThresholds defaults() {
        return new InsightThresholds(
                BigDecimal.valueOf(30),
                30,
                14,
                60,
                BigDecimal.valueOf(15),
                BigDecimal.valueOf(10),
                BigDecimal.valueOf(50000));
    }
}
