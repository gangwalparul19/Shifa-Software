package com.shifa.oms.performance.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One day in a salesperson's recent activity trend (Salesperson 360): how many
 * orders they created that day and the revenue from them.
 */
public record SalespersonDailyPoint(
        LocalDate date,
        long orders,
        BigDecimal revenue
) {
}
