package com.shifa.oms.payment.dto;

import com.shifa.oms.order.OrderEntity;
import com.shifa.oms.order.PaymentVerificationStatus;
import com.shifa.oms.order.domain.PaymentStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A row in the Payment Verifier's queue (product-audit §4.4): the order's
 * identity, the money involved, whether a screenshot is attached, and the
 * current verification state — enough for the verifier to confirm authenticity.
 */
public record PaymentQueueRow(
        Long id,
        String orderCode,
        String customerName,
        String customerMobile,
        BigDecimal totalAmount,
        BigDecimal amountReceived,
        PaymentStatus paymentStatus,
        boolean paymentScreenshotAvailable,
        PaymentVerificationStatus verificationStatus,
        LocalDateTime createdAt,
        // The name of the salesperson who punched the order (resolved from
        // created_by; full name, else username; null when unknown), so the
        // verifier can see who triggered the payment.
        String salespersonName
) {

    /** Without a resolved salesperson name (null) — kept for callers that don't resolve it. */
    public static PaymentQueueRow from(OrderEntity order) {
        return from(order, null);
    }

    public static PaymentQueueRow from(OrderEntity order, String salespersonName) {
        String key = order.getPaymentScreenshotKey();
        return new PaymentQueueRow(
                order.getId(),
                order.getOrderCode(),
                order.getCustomerName(),
                order.getCustomerMobile(),
                order.getTotalAmount(),
                order.getAmountReceived(),
                order.getPaymentStatus(),
                key != null && !key.isBlank(),
                order.getPaymentVerificationStatus(),
                order.getCreatedAt(),
                salespersonName);
    }
}
