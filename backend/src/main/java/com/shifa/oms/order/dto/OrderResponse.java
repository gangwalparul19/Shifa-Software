package com.shifa.oms.order.dto;

import com.shifa.oms.order.DeliveryMethod;
import com.shifa.oms.order.LeadSource;
import com.shifa.oms.order.OrderEntity;
import com.shifa.oms.order.OrderLineItem;
import com.shifa.oms.order.OrderSource;
import com.shifa.oms.order.OrderStatusHistory;
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
        DeliveryMethod deliveryMethod,
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
        BigDecimal discountValue,
        // Optional buyer GSTIN captured at order entry (gst-filing-compliance
        // Req 1.1); null when the buyer is unregistered.
        String buyerGstin,
        // QuikShipX shipment mirror (courier integration): the mirrored QuikShipX
        // status label (Pending/Confirmed/Tracking ID Assigned/…), the QuikShipX
        // shipping-label PDF URL, QuikShipX's own order id, and whether the
        // shipment was booked with the TEST secret. All null/false until the order
        // is published to QuikShipX; populated on the order-detail read path.
        String quikShipXStatus,
        String quikShipXLabelUrl,
        String quikShipXOrderId,
        boolean quikShipXTest,
        // The admin's reason for rejecting the order (Req 9.4); null unless the
        // order's status is REJECTED, so the salesperson can see why on the
        // order-detail view and rework it.
        String rejectionReason,
        // The categorized reason + optional note captured when a packer/admin
        // manually marks an order RTO via the Mark RTO scan flow (label redesign
        // feature). Both null unless the order was ever marked RTO that way.
        com.shifa.oms.order.RtoReason rtoReason,
        String rtoReasonNote,
        // Optional vehicle / transport reference for an in-house delivery — bus
        // vehicle no., train no., taxi registration, own van (in-house-delivery
        // feature, V63). Null for courier orders / when not captured. Together
        // with handoverName this identifies an in-house shipment, which has no AWB.
        String vehicleNumber,
        // The contact number of whoever the parcel was handed to at handover
        // (product-audit §4.3); surfaced alongside handoverName so staff can call
        // the person/operator carrying an in-house parcel.
        String handoverPhone,
        // The order's full status-history timeline (enhancement: order status
        // timeline), oldest first, so the order-detail drawer can render a
        // visual stepper with real timestamps instead of just the current pill.
        List<StatusHistoryEntryResponse> statusHistory,
        // The name of the salesperson who punched the order (resolved from
        // created_by; full name, else username; null when unknown), so the
        // order-detail drawer shows who triggered it. Populated on the detail read
        // path via withSalesperson; null on build paths that don't resolve it.
        String salespersonName,
        // The categorized rejection reason (rejection-status feature): RATE_ISSUE /
        // ADDRESS_PINCODE_ISSUE (admin REJECTED) or PAYMENT_ISSUE (PAYMENT_REJECTED
        // from the payment panel). Null unless the order was rejected. The
        // free-text note is `rejectionReason` above.
        com.shifa.oms.order.RejectReason rejectReason,
        // The payment verifier's free-text note when the payment was rejected
        // (payment_verification_note). Surfaced so the salesperson sees why the
        // payment was rejected. Null when there's no note / no payment to verify.
        String paymentVerificationNote,
        // Destination country for an international order (India/Outside India order
        // entry, V67). Null for a domestic (India) order. When set, the structured
        // city/state/postalCode are empty and the full address is in addressLine.
        String country
) {

    /**
     * One status-history row for the order-detail timeline.
     *
     * @param fromStatus null for the synthetic creation row
     * @param toStatus   the status this row transitioned into
     * @param actor      the acting username, or COURIER_API/SYSTEM
     * @param source     the transition source (e.g. ADMIN, PACKING, SYSTEM)
     * @param changedAt  when the transition was recorded
     */
    public record StatusHistoryEntryResponse(
            OrderStatus fromStatus,
            OrderStatus toStatus,
            String actor,
            String source,
            LocalDateTime changedAt
    ) {
        static StatusHistoryEntryResponse from(OrderStatusHistory h) {
            return new StatusHistoryEntryResponse(
                    h.getFromStatus(), h.getToStatus(), h.getActor(), h.getSource(), h.getChangedAt());
        }
    }

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
                order.getDeliveryMethod(),
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
                order.getDiscountValue(),
                order.getBuyerGstin(),
                null,
                null,
                null,
                false,
                order.getRejectionReason(),
                order.getRtoReason(),
                order.getRtoReasonNote(),
                order.getVehicleNumber(),
                order.getHandoverPhone(),
                order.getStatusHistory().stream().map(StatusHistoryEntryResponse::from).toList(),
                null,
                order.getRejectReason(),
                order.getPaymentVerificationNote(),
                order.getCountry());
    }

    /**
     * Returns a copy of this response with the resolved salesperson name set
     * (order-detail only). All other fields are preserved.
     */
    public OrderResponse withSalesperson(String salespersonName) {
        return new OrderResponse(
                id, orderCode, source, deliveryMethod, leadSource, leadSourceNote, customerEmail, notes, orderStatus,
                paymentStatus, customerName, customerMobile, alternateMobile, addressLine, city, state,
                postalCode, totalAmount, amountReceived, remainingAmount, codAmount, customerOutstanding,
                couponCode, discountAmount, paymentScreenshotAvailable, items, createdAt, awb, courierName,
                trackingUrl, estimatedDelivery, handoverName, packageCount, paymentVerificationStatus,
                subtotalAmount, gstAmount, discountType, discountValue, buyerGstin,
                quikShipXStatus, quikShipXLabelUrl, quikShipXOrderId, quikShipXTest, rejectionReason,
                rtoReason, rtoReasonNote, vehicleNumber, handoverPhone, statusHistory, salespersonName,
                rejectReason, paymentVerificationNote, country);
    }

    /**
     * Returns a copy of this response with the QuikShipX shipment mirror fields
     * populated (order-detail only). All other fields are preserved.
     */
    public OrderResponse withQuikShip(String quikShipXStatus, String quikShipXLabelUrl,
                                      String quikShipXOrderId, boolean quikShipXTest) {
        return new OrderResponse(
                id, orderCode, source, deliveryMethod, leadSource, leadSourceNote, customerEmail, notes, orderStatus,
                paymentStatus, customerName, customerMobile, alternateMobile, addressLine, city, state,
                postalCode, totalAmount, amountReceived, remainingAmount, codAmount, customerOutstanding,
                couponCode, discountAmount, paymentScreenshotAvailable, items, createdAt, awb, courierName,
                trackingUrl, estimatedDelivery, handoverName, packageCount, paymentVerificationStatus,
                subtotalAmount, gstAmount, discountType, discountValue, buyerGstin,
                quikShipXStatus, quikShipXLabelUrl, quikShipXOrderId, quikShipXTest, rejectionReason,
                rtoReason, rtoReasonNote, vehicleNumber, handoverPhone, statusHistory, salespersonName,
                rejectReason, paymentVerificationNote, country);
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
                deliveryMethod,
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
                discountValue,
                buyerGstin,
                quikShipXStatus,
                quikShipXLabelUrl,
                quikShipXOrderId,
                quikShipXTest,
                rejectionReason,
                rtoReason,
                rtoReasonNote,
                vehicleNumber,
                handoverPhone,
                statusHistory,
                salespersonName,
                rejectReason,
                paymentVerificationNote,
                country);
    }

    /**
     * Returns a copy with only the courier's display name set (order-detail only,
     * in-house-delivery feature). Used when a delivery partner is recorded for the
     * order but has <em>no AWB</em> — e.g. a parcel handed to a local operator or
     * sent by bus — so {@code shipmentFor} yields nothing to track yet the admin
     * should still see who has the parcel.
     */
    public OrderResponse withCourierName(String courierName) {
        return new OrderResponse(
                id, orderCode, source, deliveryMethod, leadSource, leadSourceNote, customerEmail, notes,
                orderStatus, paymentStatus, customerName, customerMobile, alternateMobile, addressLine,
                city, state, postalCode, totalAmount, amountReceived, remainingAmount, codAmount,
                customerOutstanding, couponCode, discountAmount, paymentScreenshotAvailable, items,
                createdAt, awb, courierName, trackingUrl, estimatedDelivery, handoverName, packageCount,
                paymentVerificationStatus, subtotalAmount, gstAmount, discountType, discountValue,
                buyerGstin, quikShipXStatus, quikShipXLabelUrl, quikShipXOrderId, quikShipXTest,
                rejectionReason, rtoReason, rtoReasonNote, vehicleNumber, handoverPhone, statusHistory,
                salespersonName, rejectReason, paymentVerificationNote, country);
    }
}
