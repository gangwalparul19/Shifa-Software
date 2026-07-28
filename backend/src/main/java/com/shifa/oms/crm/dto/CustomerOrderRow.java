package com.shifa.oms.crm.dto;

import com.shifa.oms.order.OrderEntity;
import com.shifa.oms.order.domain.PaymentStatus;
import com.shifa.oms.statemachine.OrderStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A compact order row in a customer's order history
 * ({@code GET /api/admin/customers/{mobile}}, "operations depth" Feature 1).
 */
public record CustomerOrderRow(
        String orderCode,
        LocalDateTime date,
        BigDecimal total,
        OrderStatus orderStatus,
        PaymentStatus paymentStatus,
        Long orderId
) {

    public static CustomerOrderRow from(OrderEntity order) {
        return new CustomerOrderRow(
                order.getOrderCode(),
                order.getCreatedAt(),
                order.getTotalAmount(),
                order.getOrderStatus(),
                order.getPaymentStatus(),
                order.getId());
    }
}
