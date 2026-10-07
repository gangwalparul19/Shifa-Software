package com.shifa.oms.insights.domain;

import java.math.BigDecimal;

/**
 * The tunable thresholds the pure {@code InsightEngine} scores against (design
 * &sect;Pure domain, &sect;Config). Built from {@code app.insights.*} config by
 * the computation service; {@link #defaults()} gives the design defaults for
 * tests and for a missing config.
 *
 * @param salesAnomalyPct     sales deviation (percent) that flags a SALES_ANOMALY (default 30)
 * @param rtoRiskThreshold    RTO risk score (0–100) at or above which an order is flagged (default 60)
 * @param courierRtoWarnPct   courier RTO percent above which a scorecard is WARNING (default 15)
 * @param codOutstandingWarn  unsettled-COD amount above which a COD build-up fires (default 50000)
 * @param locationMinOrders   minimum orders a state needs in the window before it is judged
 *                            (so a one-off order never flags a location) (default 10)
 * @param locationFailWarnPct delivery-failure percent for a state above which an
 *                            UNDERPERFORMING_LOCATION fires (default 25)
 */
public record InsightThresholds(
        BigDecimal salesAnomalyPct,
        int rtoRiskThreshold,
        BigDecimal courierRtoWarnPct,
        BigDecimal codOutstandingWarn,
        int locationMinOrders,
        BigDecimal locationFailWarnPct) {

    /** The design default thresholds (30 / 60 / 15 / 50000 / 10 / 25). */
    public static InsightThresholds defaults() {
        return new InsightThresholds(
                BigDecimal.valueOf(30),
                60,
                BigDecimal.valueOf(15),
                BigDecimal.valueOf(50000),
                10,
                BigDecimal.valueOf(25));
    }
}
