package com.shifa.oms.order.dto;

import com.shifa.oms.order.OrderEntity;
import com.shifa.oms.order.OrderLineItem;
import com.shifa.oms.order.OrderSource;
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
        LocalDateTime createdAt
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

    public static ApprovalQueueItemResponse from(OrderEntity order) {
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
                order.getCreatedAt());
    }
}
