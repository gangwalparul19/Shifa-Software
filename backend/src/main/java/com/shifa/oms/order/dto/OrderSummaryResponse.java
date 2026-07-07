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
        LocalDateTime createdAt
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
                order.getCreatedAt());
    }
}
