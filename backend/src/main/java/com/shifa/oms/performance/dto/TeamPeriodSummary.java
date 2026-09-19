package com.shifa.oms.performance.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Read-only, server-computed metrics for the selected team reporting window. */
public record TeamPeriodSummary(
        LocalDate from,
        LocalDate to,
        LocalDate previousFrom,
        LocalDate previousTo,
        long orders,
        BigDecimal revenue,
        BigDecimal averageOrderValue,
        long previousOrders,
        BigDecimal previousRevenue,
        long ordersToday,
        BigDecimal revenueToday,
        long failed,
        long rto,
        BigDecimal customerOutstanding,
        long pendingPaymentCount,
        BigDecimal pendingPaymentAmount,
        long followUpsDue,
        BigDecimal target,
        BigDecimal targetAchieved,
        Double targetProgressPct
) {
}
