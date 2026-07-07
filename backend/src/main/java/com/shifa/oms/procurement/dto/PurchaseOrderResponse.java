package com.shifa.oms.procurement.dto;

import com.shifa.oms.procurement.PurchaseOrder;
import com.shifa.oms.procurement.PurchaseOrderStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Full read projection for a purchase order, including its line items
 * ({@code GET /api/admin/purchase-orders/{id}} and the create/receive/cancel
 * responses).
 */
public record PurchaseOrderResponse(
        Long id,
        String poNumber,
        Long supplierId,
        PurchaseOrderStatus status,
        String notes,
        BigDecimal totalAmount,
        Long createdBy,
        LocalDateTime createdAt,
        LocalDateTime receivedAt,
        List<PurchaseOrderItemResponse> items
) {

    public static PurchaseOrderResponse from(PurchaseOrder po) {
        return new PurchaseOrderResponse(
                po.getId(),
                po.getPoNumber(),
                po.getSupplierId(),
                po.getStatus(),
                po.getNotes(),
                po.getTotalAmount(),
                po.getCreatedBy(),
                po.getCreatedAt(),
                po.getReceivedAt(),
                po.getItems().stream().map(PurchaseOrderItemResponse::from).toList());
    }
}
