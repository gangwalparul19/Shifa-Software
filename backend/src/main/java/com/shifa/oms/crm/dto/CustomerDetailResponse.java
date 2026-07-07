package com.shifa.oms.crm.dto;

import java.util.List;

/**
 * A single customer's detail ({@code GET /api/admin/customers/{mobile}},
 * "operations depth" Feature 1): the summary plus their full order history as
 * compact rows (newest first).
 */
public record CustomerDetailResponse(
        CustomerSummaryResponse summary,
        List<CustomerOrderRow> orders
) {
}
