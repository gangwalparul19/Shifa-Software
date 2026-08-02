package com.shifa.oms.order.dto;

import com.shifa.oms.order.OrderEntity;
import com.shifa.oms.order.OrderSource;
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
        OrderSource channel
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
                // The channel as stored, not canonicalised (spec
                // shopify-quikshipx-order-sync, Req 1.5), matching what OrderResponse
                // already returns for the detail view. Folding the legacy SALESPERSON and
                // STOREFRONT values is the presentation layer's job, so the API stays an
                // honest report of what is on the row. Appended LAST so no existing
                // positional construction breaks.
                order.getSource());
    }
}
