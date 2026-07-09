package com.shifa.oms.reporting.dto;

import java.math.BigDecimal;

/**
 * The headline metrics accompanying a report (Req 19.3, 19.4, 19.7): total sales
 * and order count over the window, the previous-period sales percentage change,
 * and the top performers. {@code salesChangeApplicable} is {@code false} when the
 * previous period had zero sales but the current period did not.
 */
public record ReportSummary(
        BigDecimal totalSales,
        long orderCount,
        boolean salesChangeApplicable,
        BigDecimal salesChangePercent,
        Long topSalespersonId,
        String topSalespersonName,
        String topProduct,
        String topState) {
}
