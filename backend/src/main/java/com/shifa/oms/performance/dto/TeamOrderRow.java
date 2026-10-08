package com.shifa.oms.performance.dto;

import com.shifa.oms.order.domain.PaymentStatus;
import com.shifa.oms.statemachine.OrderStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Lightweight order row for the Team Lead's own/team tabs. */
public record TeamOrderRow(
        Long id,
        String orderCode,
        String customerName,
        String salespersonName,
        BigDecimal totalAmount,
        OrderStatus orderStatus,
        PaymentStatus paymentStatus,
        LocalDateTime createdAt
) {
}
