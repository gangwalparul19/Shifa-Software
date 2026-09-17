package com.shifa.oms.order.dto;

import com.shifa.oms.statemachine.OrderStatus;

import java.util.List;

/** Read-only eligibility result used before an admin bulk action. */
public record BulkPreviewResponse(
        String action,
        int requested,
        List<EligibleItem> eligible,
        List<IneligibleItem> ineligible
) {
    public record EligibleItem(Long id, String orderCode, OrderStatus currentStatus) { }
    public record IneligibleItem(Long id, String orderCode, OrderStatus currentStatus, String reason) { }
}
