package com.shifa.oms.insights.domain;

/**
 * The families of statistical insight the pure {@code InsightEngine} can produce
 * (design &sect;Pure domain). The engine is focused on actionable sales &amp;
 * marketing decisions and fulfilment risk — stock/returns families were
 * deliberately removed.
 *
 * <ul>
 *   <li>{@link #SALES_ANOMALY} — the current window's revenue deviates sharply
 *       from the preceding equal-length window (GLOBAL scope).</li>
 *   <li>{@link #TOP_SALES_LOCATION} — the strongest-selling state in the window,
 *       a cue to double down on marketing there (GLOBAL scope).</li>
 *   <li>{@link #UNDERPERFORMING_LOCATION} — a state with meaningful order volume
 *       but a high delivery-failure/RTO rate: marketing spend there isn't
 *       converting into delivered sales, so fix delivery or cut spend (GLOBAL
 *       scope).</li>
 *   <li>{@link #RTO_RISK} — an open order carries a high return-to-origin /
 *       delivery-failure risk score (ORDER scope).</li>
 *   <li>{@link #COURIER_SCORECARD} — a per-courier delivery / RTO / transit
 *       performance summary (COURIER scope).</li>
 *   <li>{@link #COD_OUTSTANDING_BUILDUP} — unsettled COD receivables exceed the
 *       configured amount (GLOBAL scope).</li>
 *   <li>{@link #LEAD_SOURCE_CONVERSION} — the best- and worst-converting lead
 *       channels by {@code won / leads}, a cue to push the winning channel
 *       (GLOBAL scope).</li>
 * </ul>
 */
public enum InsightType {
    SALES_ANOMALY,
    TOP_SALES_LOCATION,
    UNDERPERFORMING_LOCATION,
    RTO_RISK,
    COURIER_SCORECARD,
    COD_OUTSTANDING_BUILDUP,
    LEAD_SOURCE_CONVERSION;

    /**
     * Case-insensitive parse of an insight type from a request path/param value,
     * mirroring {@link com.shifa.oms.reporting.domain.ReportType#from(String)}.
     * Accepts both the canonical enum name and a hyphenated lower-case form.
     */
    public static InsightType from(String value) {
        if (value == null) {
            throw new IllegalArgumentException("insight type is required");
        }
        return switch (value.trim().toLowerCase()) {
            case "sales_anomaly", "sales-anomaly", "sales" -> SALES_ANOMALY;
            case "top_sales_location", "top-sales-location", "top-location" -> TOP_SALES_LOCATION;
            case "underperforming_location", "underperforming-location", "weak-location" ->
                    UNDERPERFORMING_LOCATION;
            case "rto_risk", "rto-risk", "rto" -> RTO_RISK;
            case "courier_scorecard", "courier-scorecard", "courier" -> COURIER_SCORECARD;
            case "cod_outstanding_buildup", "cod-outstanding-buildup", "cod" -> COD_OUTSTANDING_BUILDUP;
            case "lead_source_conversion", "lead-source-conversion", "lead-source" ->
                    LEAD_SOURCE_CONVERSION;
            default -> throw new IllegalArgumentException("unknown insight type: " + value);
        };
    }
}
