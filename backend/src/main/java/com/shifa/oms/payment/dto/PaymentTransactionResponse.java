package com.shifa.oms.payment.dto;

import com.shifa.oms.payment.PaymentTransaction;
import com.shifa.oms.payment.PaymentTransactionStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Admin-facing view of an online-payment attempt for an order
 * ({@code GET /api/orders/{id}/payments}).
 */
public record PaymentTransactionResponse(
        Long id,
        Long orderId,
        String gateway,
        String gatewayOrderId,
        String gatewayPaymentId,
        BigDecimal amount,
        PaymentTransactionStatus status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static PaymentTransactionResponse from(PaymentTransaction txn) {
        return new PaymentTransactionResponse(
                txn.getId(),
                txn.getOrderId(),
                txn.getGateway(),
                txn.getGatewayOrderId(),
                txn.getGatewayPaymentId(),
                txn.getAmount(),
                txn.getStatus(),
                txn.getCreatedAt(),
                txn.getUpdatedAt());
    }
}
