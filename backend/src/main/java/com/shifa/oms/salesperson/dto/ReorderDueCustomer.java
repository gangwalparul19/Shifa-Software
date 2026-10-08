package com.shifa.oms.salesperson.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A customer predicted to be due for a repeat purchase, for the salesperson's
 * proactive reorder list ({@code GET /api/my-day/reorder-due}). The prediction is
 * a simple, explainable rule: the average interval between the customer's past
 * orders projected from their last order. Only customers with ≥2 orders (so a
 * cadence exists) whose predicted date is near/past are returned, soonest-due
 * (most overdue) first.
 *
 * @param overdueDays days since the predicted reorder date — positive = overdue,
 *                    negative = due in that many days.
 */
public record ReorderDueCustomer(
        String mobile,
        String customerName,
        LocalDate lastOrderDate,
        LocalDate predictedReorderDate,
        long avgIntervalDays,
        long overdueDays,
        long orderCount,
        BigDecimal totalValue) {
}
