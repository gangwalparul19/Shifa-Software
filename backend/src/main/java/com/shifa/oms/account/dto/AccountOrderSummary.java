package com.shifa.oms.account.dto;

import com.shifa.oms.order.OrderEntity;
import com.shifa.oms.order.domain.PaymentStatus;
import com.shifa.oms.statemachine.OrderStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Compact order projection for a customer's "My Orders" history list. Exposes
 * only what the customer needs: the order code (used for tracking + invoice),
 * the placement date, the total, and the lifecycle + payment status.
 */
public record AccountOrderSummary(
        String orderCode,
        LocalDateTime createdAt,
        BigDecimal totalAmount,
        OrderStatus orderStatus,
        PaymentStatus paymentStatus,
        int itemCount) {

    public static AccountOrderSummary from(OrderEntity order) {
        return new AccountOrderSummary(
                order.getOrderCode(),
                order.getCreatedAt(),
                order.getTotalAmount(),
                order.getOrderStatus(),
                order.getPaymentStatus(),
                order.getLineItems().size());
    }
}
