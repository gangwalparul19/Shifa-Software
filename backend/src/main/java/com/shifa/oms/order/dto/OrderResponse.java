package com.shifa.oms.order.dto;

import com.shifa.oms.order.LeadSource;
import com.shifa.oms.order.OrderEntity;
import com.shifa.oms.order.OrderLineItem;
import com.shifa.oms.order.OrderSource;
import com.shifa.oms.order.domain.PaymentStatus;
import com.shifa.oms.statemachine.OrderStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Full order projection returned by {@code POST /api/orders},
 * {@code POST /api/checkout}, and {@code GET /api/orders/{id}}. Exposes the
 * payment tracking fields (Req 21.1) plus line items and lifecycle status.
 *
 * <p>The shipment fields ({@code awb}, {@code courierName}, {@code trackingUrl},
 * {@code estimatedDelivery}) are populated only on the single-order detail
 * response, and only when a courier record exists for the order; otherwise they
 * are {@code null}. They are built from the courier module reusing the same
 * tracking-link logic as {@code GET /api/track} (Req 13.4, 14.1).
 */
public record OrderResponse(
        Long id,
        String orderCode,
        OrderSource source,
        LeadSource leadSource,
        String leadSourceNote,
        String customerEmail,
        OrderStatus orderStatus,
        PaymentStatus paymentStatus,
        String customerName,
        String customerMobile,
        String addressLine,
        String city,
        String state,
        String postalCode,
        BigDecimal totalAmount,
        BigDecimal amountReceived,
        BigDecimal remainingAmount,
        BigDecimal codAmount,
        BigDecimal customerOutstanding,
        String couponCode,
        BigDecimal discountAmount,
        boolean paymentScreenshotAvailable,
        List<LineItemResponse> items,
        LocalDateTime createdAt,
        String awb,
        String courierName,
        String trackingUrl,
        LocalDate estimatedDelivery
) {

    /** A single order line in the response. */
    public record LineItemResponse(
            Long productId,
            String productName,
            String hsnCode,
            BigDecimal gstRate,
            int quantity,
            BigDecimal rate,
            BigDecimal lineTotal
    ) {
        static LineItemResponse from(OrderLineItem item) {
            return new LineItemResponse(
                    item.getProductId(),
                    item.getProductName(),
                    item.getHsnCode(),
                    item.getGstRate(),
                    item.getQuantity(),
                    item.getRate(),
                    item.getLineTotal());
        }
    }

    public static OrderResponse from(OrderEntity order) {
        List<LineItemResponse> items = order.getLineItems().stream()
                .map(LineItemResponse::from)
                .toList();
        return new OrderResponse(
                order.getId(),
                order.getOrderCode(),
                order.getSource(),
                order.getLeadSource(),
                order.getLeadSourceNote(),
                order.getCustomerEmail(),
                order.getOrderStatus(),
                order.getPaymentStatus(),
                order.getCustomerName(),
                order.getCustomerMobile(),
                order.getAddressLine(),
                order.getCity(),
                order.getState(),
                order.getPostalCode(),
                order.getTotalAmount(),
                order.getAmountReceived(),
                order.getRemainingAmount(),
                order.getCodAmount(),
                order.getCustomerOutstanding(),
                order.getCouponCode(),
                order.getDiscountAmount(),
                order.getPaymentScreenshotKey() != null && !order.getPaymentScreenshotKey().isBlank(),
                items,
                order.getCreatedAt(),
                null,
                null,
                null,
                null);
    }

    /**
     * Returns a copy of this response with the shipment fields populated from a
     * courier record (order-detail only). All other fields are preserved.
     */
    public OrderResponse withShipment(String awb, String courierName,
                                      String trackingUrl, LocalDate estimatedDelivery) {
        return new OrderResponse(
                id,
                orderCode,
                source,
                leadSource,
                leadSourceNote,
                customerEmail,
                orderStatus,
                paymentStatus,
                customerName,
                customerMobile,
                addressLine,
                city,
                state,
                postalCode,
                totalAmount,
                amountReceived,
                remainingAmount,
                codAmount,
                customerOutstanding,
                couponCode,
                discountAmount,
                paymentScreenshotAvailable,
                items,
                createdAt,
                awb,
                courierName,
                trackingUrl,
                estimatedDelivery);
    }
}
