package com.shifa.oms.order.dto;

import com.shifa.oms.order.DeliveryMethod;
import com.shifa.oms.order.OrderEntity;
import com.shifa.oms.order.OrderLineItem;
import com.shifa.oms.order.OrderSource;
import com.shifa.oms.order.PaymentVerificationStatus;
import com.shifa.oms.order.domain.PaymentStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Review projection for the admin approval queue
 * ({@code GET /api/admin/orders/approval-queue}, Req 9.1, 9.2).
 *
 * <p>Carries everything the admin needs on screen to decide approve/reject:
 * the order code, customer name / mobile / full address, the line items (name,
 * qty, applied rate, line total), the amounts ({@code Total_Amount},
 * {@code Amount_Received}, {@code COD_Amount}), the {@link PaymentStatus},
 * whether a payment screenshot exists (the image itself is fetched via
 * {@code GET /api/orders/&#123;id&#125;/payment-screenshot}), and provenance
 * ({@code createdAt}, {@code source}, {@code createdBy}).
 */
public record ApprovalQueueItemResponse(
        Long id,
        String orderCode,
        OrderSource source,
        Long createdBy,
        String customerName,
        String customerMobile,
        String addressLine,
        String city,
        String state,
        String postalCode,
        BigDecimal totalAmount,
        BigDecimal amountReceived,
        BigDecimal codAmount,
        PaymentStatus paymentStatus,
        boolean paymentScreenshotAvailable,
        List<LineItemResponse> items,
        LocalDateTime createdAt,
        // The order's current delivery method (default IN_HOUSE at entry), shown
        // to the admin so they can review/override it as part of approving
        // (in-house-delivery feature: admin decides the delivery partner).
        DeliveryMethod deliveryMethod,
        // The name of the salesperson who punched the order (resolved from
        // created_by; full name, else username; null when unknown), so the
        // reviewing admin can see who triggered it.
        String salespersonName,
        // Payment authenticity verification state (payment-verification-gated
        // approval): PENDING / VERIFIED / REJECTED, or null for a pure-COD order
        // (nothing to verify). The admin cannot approve until this is VERIFIED or
        // null; the queue shows a verified icon when VERIFIED.
        PaymentVerificationStatus paymentVerificationStatus,
        // Other order codes whose payment proof is byte-identical to this order's
        // (duplicate-screenshot detection, V72). Non-empty = a duplicate flag is
        // shown and the order is excluded from "approve all (no duplicates)".
        List<String> duplicateOrderCodes,
        // The order/packaging note (nullable) — surfaced so the admin sees
        // special instructions or context the salesperson attached to the order.
        String notes
) {

    /** A single order line in the review projection. */
    public record LineItemResponse(
            Long productId,
            String productName,
            int quantity,
            BigDecimal rate,
            BigDecimal lineTotal
    ) {
        static LineItemResponse from(OrderLineItem item) {
            return new LineItemResponse(
                    item.getProductId(),
                    item.getProductName(),
                    item.getQuantity(),
                    item.getRate(),
                    item.getLineTotal());
        }
    }

    /** Without a resolved salesperson name (null) — kept for callers that don't resolve it. */
    public static ApprovalQueueItemResponse from(OrderEntity order) {
        return from(order, null, List.of());
    }

    /** Without a duplicate-proof flag — kept for callers that don't resolve it. */
    public static ApprovalQueueItemResponse from(OrderEntity order, String salespersonName) {
        return from(order, salespersonName, List.of());
    }

    public static ApprovalQueueItemResponse from(OrderEntity order, String salespersonName,
                                                 List<String> duplicateOrderCodes) {
        List<LineItemResponse> items = order.getLineItems().stream()
                .map(LineItemResponse::from)
                .toList();
        String screenshotKey = order.getPaymentScreenshotKey();
        return new ApprovalQueueItemResponse(
                order.getId(),
                order.getOrderCode(),
                order.getSource(),
                order.getCreatedBy(),
                order.getCustomerName(),
                order.getCustomerMobile(),
                order.getAddressLine(),
                order.getCity(),
                order.getState(),
                order.getPostalCode(),
                order.getTotalAmount(),
                order.getAmountReceived(),
                order.getCodAmount(),
                order.getPaymentStatus(),
                screenshotKey != null && !screenshotKey.isBlank(),
                items,
                order.getCreatedAt(),
                order.getDeliveryMethod(),
                salespersonName,
                order.getPaymentVerificationStatus(),
                duplicateOrderCodes == null ? List.of() : duplicateOrderCodes,
                order.getNotes());
    }
}
