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
import java.util.Map;

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
        String notes,
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

    /**
     * A single order line in the response.
     *
     * <p>{@code imageKey} is the storage key of the line product's primary
     * (first PUBLISHED) image, or {@code null} when the product has no published
     * image / is unknown. It is populated only on the order-detail response
     * (built with an image map); other build paths leave it {@code null} and the
     * UI falls back to a placeholder (order module, item 1).
     */
    public record LineItemResponse(
            Long productId,
            String productName,
            String hsnCode,
            BigDecimal gstRate,
            int quantity,
            BigDecimal rate,
            BigDecimal lineTotal,
            String imageKey
    ) {
        static LineItemResponse from(OrderLineItem item) {
            return from(item, null);
        }

        static LineItemResponse from(OrderLineItem item, String imageKey) {
            return new LineItemResponse(
                    item.getProductId(),
                    item.getProductName(),
                    item.getHsnCode(),
                    item.getGstRate(),
                    item.getQuantity(),
                    item.getRate(),
                    item.getLineTotal(),
                    imageKey);
        }
    }

    public static OrderResponse from(OrderEntity order) {
        return from(order, Map.of());
    }

    /**
     * Full projection whose line items carry each product's primary image key,
     * resolved from the given {@code imageKeysByProductId} map (order module,
     * item 1). Products absent from the map (or with a {@code null} product id)
     * yield a {@code null} image key. Used by the order-detail read path, which
     * batch-loads images for the line products to avoid an N+1.
     */
    public static OrderResponse from(OrderEntity order, Map<Long, String> imageKeysByProductId) {
        Map<Long, String> imageKeys = imageKeysByProductId != null ? imageKeysByProductId : Map.of();
        List<LineItemResponse> items = order.getLineItems().stream()
                .map(item -> LineItemResponse.from(
                        item,
                        item.getProductId() != null ? imageKeys.get(item.getProductId()) : null))
                .toList();
        return new OrderResponse(
                order.getId(),
                order.getOrderCode(),
                order.getSource(),
                order.getLeadSource(),
                order.getLeadSourceNote(),
                order.getCustomerEmail(),
                order.getNotes(),
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
                notes,
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
