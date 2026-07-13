package com.shifa.oms.crm.dto;

import com.shifa.oms.crm.domain.CustomerRiskLevel;

/**
 * A customer's delivery-reliability risk assessment (FEATURE-ROADMAP §1.2),
 * returned both on the 360 profile and by the lightweight order-entry lookup
 * ({@code GET /api/admin/customers/{mobile}/risk}) so a salesperson is nudged
 * toward prepaid for risky customers before confirming a COD order.
 *
 * @param mobile              the customer's mobile
 * @param level               the computed risk band
 * @param failedDeliveryCount concluded failed deliveries in the customer's history
 * @param deliveredCount      concluded successful deliveries
 * @param failureRate         failed / (failed + delivered), 0..1
 * @param priorOrderCount     how many orders the customer already has (0 = brand new)
 * @param repeatBuyer         whether the customer has ordered more than once
 * @param message             a short human-readable nudge for the UI
 */
public record CustomerRiskResponse(
        String mobile,
        CustomerRiskLevel level,
        long failedDeliveryCount,
        long deliveredCount,
        double failureRate,
        long priorOrderCount,
        boolean repeatBuyer,
        String message
) {
}
