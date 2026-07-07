package com.shifa.oms.procurement.dto;

import com.shifa.oms.procurement.PurchaseOrder;
import com.shifa.oms.procurement.PurchaseOrderStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Lightweight read projection for the paged PO listing
 * ({@code GET /api/admin/purchase-orders}) — the line items are omitted so the
 * grid stays small; {@code itemCount} carries the number of lines.
 */
public record PurchaseOrderSummaryResponse(
        Long id,
        String poNumber,
        Long supplierId,
        PurchaseOrderStatus status,
        BigDecimal totalAmount,
        int itemCount,
        LocalDateTime createdAt,
        LocalDateTime receivedAt
) {

    public static PurchaseOrderSummaryResponse from(PurchaseOrder po) {
        return new PurchaseOrderSummaryResponse(
                po.getId(),
                po.getPoNumber(),
                po.getSupplierId(),
                po.getStatus(),
                po.getTotalAmount(),
                po.getItems().size(),
                po.getCreatedAt(),
                po.getReceivedAt());
    }
}
