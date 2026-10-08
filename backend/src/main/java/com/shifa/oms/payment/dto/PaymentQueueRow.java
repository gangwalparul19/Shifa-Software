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
        String salespersonName,
        // Other orders whose payment proof is byte-identical to this one
        // (duplicate-screenshot detection, V72), each as an {orderId, orderCode}
        // ref so the UI can link the duplicate: click the code to view THAT
        // order's screenshot (by id) and open its details (by code). Empty when
        // the proof is unique — a non-empty list is a fraud/mistake flag.
        java.util.List<DuplicateOrderRef> duplicateOrders,
        // Where the order originated (SALESPERSON / SHOPIFY / STORE / STOREFRONT),
        // so the queue can be filtered Portal (non-Shopify) vs Shopify.
        com.shifa.oms.order.OrderSource source
) {

    /**
     * A reference to another order sharing this order's payment proof: its id (to
     * fetch that order's screenshot) and its human order code (to display + link
     * to its details). Duplicate-screenshot detection, V72.
     */
    public record DuplicateOrderRef(Long orderId, String orderCode) {
    }

    /** Without a resolved salesperson name (null) — kept for callers that don't resolve it. */
    public static PaymentQueueRow from(OrderEntity order) {
        return from(order, null);
    }

    public static PaymentQueueRow from(OrderEntity order, String salespersonName) {
        return from(order, salespersonName, java.util.List.of());
    }

    public static PaymentQueueRow from(OrderEntity order, String salespersonName,
                                       java.util.List<DuplicateOrderRef> duplicateOrders) {
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
                salespersonName,
                duplicateOrders == null ? java.util.List.of() : duplicateOrders,
                order.getSource());
    }
}
