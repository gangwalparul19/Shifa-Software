package com.shifa.oms.procurement.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Create payload for a purchase order ({@code POST /api/admin/purchase-orders}):
 * a supplier, optional notes, and at least one line item.
 */
public record CreatePurchaseOrderRequest(
        @NotNull(message = "supplierId is required")
        Long supplierId,

        @Size(max = 1000, message = "notes must be at most 1000 characters")
        String notes,

        @NotEmpty(message = "at least one line item is required")
        @Valid
        List<PurchaseOrderItemRequest> items
) {
}
