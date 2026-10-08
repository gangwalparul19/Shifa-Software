package com.shifa.oms.crm.dto;

import java.util.List;

/**
 * The full "Customer 360" profile (FEATURE-ROADMAP §1.1), returned by
 * {@code GET /api/admin/customers/{mobile}/profile}: the aggregated summary, the
 * derived delivery/money metrics, the risk assessment, the products the customer
 * has bought, a status breakdown, their tags and notes timeline, and the full
 * order history.
 *
 * @param summary        the aggregated customer summary (name, LTV, first/last order …)
 * @param metrics        derived delivery-reliability + outstanding metrics
 * @param risk           the delivery-reliability risk assessment
 * @param topProducts    products bought, highest spend first
 * @param statusBreakdown order counts per lifecycle status (lifecycle order)
 * @param tags           the customer's segment tags
 * @param notes          the staff notes timeline (newest first)
 * @param orders         the customer's order history (newest first)
 */
public record CustomerProfileResponse(
        CustomerSummaryResponse summary,
        CustomerMetrics metrics,
        CustomerRiskResponse risk,
        List<TopProductRow> topProducts,
        List<StatusCount> statusBreakdown,
        List<String> tags,
        List<CustomerNoteResponse> notes,
        List<CustomerOrderRow> orders
) {
}
