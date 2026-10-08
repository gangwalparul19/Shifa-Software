package com.shifa.oms.performance.dto;

import com.shifa.oms.order.OrderEntity;
import com.shifa.oms.order.domain.PaymentStatus;
import com.shifa.oms.statemachine.OrderStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A compact recent-order row in a salesperson's 360 profile.
 */
public record SalespersonOrderRow(
        String orderCode,
        String customerName,
        BigDecimal totalAmount,
        OrderStatus orderStatus,
        PaymentStatus paymentStatus,
        LocalDateTime createdAt
) {

    public static SalespersonOrderRow from(OrderEntity o) {
        return new SalespersonOrderRow(
                o.getOrderCode(),
                o.getCustomerName(),
                o.getTotalAmount(),
                o.getOrderStatus(),
                o.getPaymentStatus(),
                o.getCreatedAt());
    }
}
