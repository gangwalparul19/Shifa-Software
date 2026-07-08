package com.shifa.oms.insights.domain;

import java.math.BigDecimal;

/**
 * Pre-summed revenue for the current and immediately preceding equal-length
 * windows (design &sect;Pure domain; Req 3.1–3.4), the input to sales-anomaly
 * detection. Both totals exclude REJECTED/CANCELLED orders (revenue definition
 * applied upstream by the service).
 *
 * @param currentTotal  revenue over the current window
 * @param previousTotal revenue over the preceding equal-length window
 */
public record SalesWindow(BigDecimal currentTotal, BigDecimal previousTotal) {
}
