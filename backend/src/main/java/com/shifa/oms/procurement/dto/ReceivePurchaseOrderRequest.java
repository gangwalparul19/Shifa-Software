package com.shifa.oms.procurement.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * Receiving payload for a purchase order ({@code POST
 * /api/admin/purchase-orders/{id}/receive}): the per-line received quantities
 * for this receiving event.
 */
public record ReceivePurchaseOrderRequest(
        @NotEmpty(message = "at least one receive line is required")
        @Valid
        List<ReceiveLineRequest> lines
) {
}
