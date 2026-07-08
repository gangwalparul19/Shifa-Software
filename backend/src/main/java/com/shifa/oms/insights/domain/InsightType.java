package com.shifa.oms.insights.domain;

/**
 * The families of statistical insight the pure {@code InsightEngine} can produce
 * (design &sect;Pure domain; Req 3.1, 4.1, 5.1, 6.1, 7.1, 8.1).
 *
 * <ul>
 *   <li>{@link #SALES_ANOMALY} — the current window's revenue deviates sharply
 *       from the preceding equal-length window (GLOBAL scope).</li>
 *   <li>{@link #LOW_STOCK_REORDER} — a product's projected days-of-cover has
 *       fallen below the reorder threshold (PRODUCT scope).</li>
 *   <li>{@link #RTO_RISK} — an open order carries a high return-to-origin /
 *       delivery-failure risk score (ORDER scope).</li>
 *   <li>{@link #COURIER_SCORECARD} — a per-courier delivery / RTO / transit
 *       performance summary (COURIER scope).</li>
 *   <li>{@link #RETURN_RATE_ANOMALY} — the window's return rate exceeds the
 *       configured threshold (GLOBAL scope).</li>
 *   <li>{@link #COD_OUTSTANDING_BUILDUP} — unsettled COD receivables exceed the
 *       configured amount (GLOBAL scope).</li>
 *   <li>{@link #LEAD_SOURCE_CONVERSION} — the best- and worst-converting lead
 *       channels by {@code won / leads} (GLOBAL scope).</li>
 * </ul>
 */
public enum InsightType {
    SALES_ANOMALY,
    LOW_STOCK_REORDER,
    RTO_RISK,
    COURIER_SCORECARD,
    RETURN_RATE_ANOMALY,
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
            case "low_stock_reorder", "low-stock-reorder", "reorder" -> LOW_STOCK_REORDER;
            case "rto_risk", "rto-risk", "rto" -> RTO_RISK;
            case "courier_scorecard", "courier-scorecard", "courier" -> COURIER_SCORECARD;
            case "return_rate_anomaly", "return-rate-anomaly", "return-rate" -> RETURN_RATE_ANOMALY;
            case "cod_outstanding_buildup", "cod-outstanding-buildup", "cod" -> COD_OUTSTANDING_BUILDUP;
            case "lead_source_conversion", "lead-source-conversion", "lead-source" ->
                    LEAD_SOURCE_CONVERSION;
            default -> throw new IllegalArgumentException("unknown insight type: " + value);
        };
    }
}
