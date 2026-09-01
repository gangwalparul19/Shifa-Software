package com.shifa.oms.order.dto;

import com.shifa.oms.order.OrderEntity;
import com.shifa.oms.order.domain.PaymentStatus;
import com.shifa.oms.statemachine.OrderStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Compact order projection returned by the search endpoint
 * {@code GET /api/orders?search=} (Req 22.1). Omits line items and address
 * detail for a lightweight result list.
 */
public record OrderSummaryResponse(
        Long id,
        String orderCode,
        String customerName,
        String customerMobile,
        OrderStatus orderStatus,
        PaymentStatus paymentStatus,
        BigDecimal totalAmount,
        BigDecimal codAmount,
        LocalDateTime createdAt,
        // The mirrored QuikShipX status label (Pending / Confirmed / Tracking ID
        // Assigned / In Transit / …), shown as a chip on the Orders list. Null when
        // the order was never published to QuikShipX. Populated by the admin list
        // read path (batch-loaded); null on the lightweight search results.
        String quikShipXStatus,
        // QuikShipX's own order id (the number quoted to track on their portal) and
        // the allotted AWB, surfaced so the Orders list/chip can show them too.
        String quikShipXOrderId,
        String quikShipXAwb
) {

    public static OrderSummaryResponse from(OrderEntity order) {
        return new OrderSummaryResponse(
                order.getId(),
                order.getOrderCode(),
                order.getCustomerName(),
                order.getCustomerMobile(),
                order.getOrderStatus(),
                order.getPaymentStatus(),
                order.getTotalAmount(),
                order.getCodAmount(),
                order.getCreatedAt(),
                null,
                null,
                null);
    }

    /** Returns a copy carrying the QuikShipX mirror fields (Orders-list enrichment). */
    public OrderSummaryResponse withQuikShip(String quikShipXStatus, String quikShipXOrderId,
                                             String quikShipXAwb) {
        return new OrderSummaryResponse(id, orderCode, customerName, customerMobile, orderStatus,
                paymentStatus, totalAmount, codAmount, createdAt,
                quikShipXStatus, quikShipXOrderId, quikShipXAwb);
    }
}
