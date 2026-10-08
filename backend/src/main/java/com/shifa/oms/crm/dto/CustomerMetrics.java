package com.shifa.oms.crm.dto;

import java.math.BigDecimal;

/**
 * Derived delivery-reliability + money metrics for a customer's 360 profile
 * (FEATURE-ROADMAP §1.1/§1.2). All values are computed from the customer's
 * order history — nothing here is stored.
 *
 * @param deliveredCount      concluded successful deliveries (delivered / COD collected / closed)
 * @param failedDeliveryCount concluded failed deliveries (customer rejected / RTO / delivery failed / courier lost)
 * @param inFlightCount       orders still moving through the pipeline (not yet concluded)
 * @param cancelledCount      orders rejected at approval or cancelled
 * @param successRate         delivered / (delivered + failed), 0..1 (0 when no concluded deliveries)
 * @param outstanding         total amount the customer still owes across their orders
 */
public record CustomerMetrics(
        long deliveredCount,
        long failedDeliveryCount,
        long inFlightCount,
        long cancelledCount,
        double successRate,
        BigDecimal outstanding
) {
}
