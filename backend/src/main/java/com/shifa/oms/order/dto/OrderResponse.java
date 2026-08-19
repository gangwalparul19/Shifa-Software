package com.shifa.oms.order.dto;

import com.shifa.oms.order.LeadSource;
import com.shifa.oms.order.OrderEntity;
import com.shifa.oms.order.OrderLineItem;
import com.shifa.oms.order.OrderSource;
import com.shifa.oms.order.PaymentVerificationStatus;
import com.shifa.oms.order.domain.DiscountType;
import com.shifa.oms.order.domain.OrderPricing;
import com.shifa.oms.order.domain.PaymentStatus;
import com.shifa.oms.statemachine.OrderStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
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
        String alternateMobile,
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
        LocalDate estimatedDelivery,
        String handoverName,
        int packageCount,
        PaymentVerificationStatus paymentVerificationStatus,
        // product-catalog-pricing-gst Req 8: GST-inclusive subtotal (Σ line totals),
        // the aggregate GST contained within the total, and the order-level discount
        // type/value as entered. Computed at read time from the persisted lines +
        // discount, so list/detail/invoice agree.
        BigDecimal subtotalAmount,
        BigDecimal gstAmount,
        String discountType,
        BigDecimal discountValue
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
            String imageKey,
            // Per-line GST amount extracted from the GST-inclusive, post-discount
            // line net (product-catalog-pricing-gst Req 7). Computed at read time.
            BigDecimal gstAmount
    ) {
        static LineItemResponse from(OrderLineItem item, String imageKey, BigDecimal gstAmount) {
            return new LineItemResponse(
                    item.getProductId(),
                    item.getProductName(),
                    item.getHsnCode(),
                    item.getGstRate(),
                    item.getQuantity(),
                    item.getRate(),
                    item.getLineTotal(),
                    imageKey,
                    gstAmount);
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
        List<OrderLineItem> lineItems = order.getLineItems();

        // Re-price the persisted lines through the pure engine to derive the
        // GST-inclusive subtotal, per-line GST, and aggregate GST (Req 7, 8). The
        // resolved discount_amount is reused as a FLAT discount so apportionment
        // reproduces exactly what was captured at creation (the type only affects
        // how the amount is derived, not how it is split across lines).
        List<OrderPricing.LineInput> pricingLines = new ArrayList<>(lineItems.size());
        BigDecimal grossSubtotal = BigDecimal.ZERO;
        for (OrderLineItem item : lineItems) {
            OrderPricing.LineInput input =
                    new OrderPricing.LineInput(item.getQuantity(), item.getRate(), item.getGstRate());
            pricingLines.add(input);
            grossSubtotal = grossSubtotal.add(input.lineTotal());
        }
        // Reuse the resolved discount_amount as a FLAT discount, clamped to the
        // subtotal so read-time repricing never fails on edge/legacy data.
        BigDecimal discountAmount = order.getDiscountAmount();
        BigDecimal effective = discountAmount == null ? BigDecimal.ZERO : discountAmount;
        if (effective.compareTo(grossSubtotal) > 0) {
            effective = grossSubtotal;
        }
        OrderPricing.DiscountSpec spec = effective.signum() > 0
                ? OrderPricing.DiscountSpec.of(DiscountType.FLAT, effective)
                : OrderPricing.DiscountSpec.NONE;
        OrderPricing.PricedOrder priced = OrderPricing.compute(pricingLines, spec);

        List<LineItemResponse> items = new ArrayList<>(lineItems.size());
        for (int i = 0; i < lineItems.size(); i++) {
            OrderLineItem item = lineItems.get(i);
            String imageKey = item.getProductId() != null ? imageKeys.get(item.getProductId()) : null;
            BigDecimal lineGst = priced.lines().get(i).gstAmount();
            items.add(LineItemResponse.from(item, imageKey, lineGst));
        }
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
                order.getAlternateMobile(),
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
                null,
                order.getHandoverName(),
                order.getPackageCount(),
                order.getPaymentVerificationStatus(),
                priced.subtotal(),
                priced.gstTotal(),
                order.getDiscountType(),
                order.getDiscountValue());
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
                alternateMobile,
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
                estimatedDelivery,
                handoverName,
                packageCount,
                paymentVerificationStatus,
                subtotalAmount,
                gstAmount,
                discountType,
                discountValue);
    }
}
