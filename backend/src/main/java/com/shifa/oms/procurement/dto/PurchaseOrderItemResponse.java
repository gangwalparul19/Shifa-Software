package com.shifa.oms.procurement.dto;

import com.shifa.oms.procurement.PurchaseOrderItem;

import java.math.BigDecimal;

/**
 * Read projection for a single PO line item.
 */
public record PurchaseOrderItemResponse(
        Long id,
        Long productId,
        int quantity,
        BigDecimal unitCost,
        int receivedQuantity,
        BigDecimal lineTotal
) {

    public static PurchaseOrderItemResponse from(PurchaseOrderItem item) {
        return new PurchaseOrderItemResponse(
                item.getId(),
                item.getProductId(),
                item.getQuantity(),
                item.getUnitCost(),
                item.getReceivedQuantity(),
                item.lineTotal());
    }
}
