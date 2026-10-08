package com.shifa.oms.packing.dto;

import com.shifa.oms.order.OrderEntity;
import com.shifa.oms.order.domain.PaymentStatus;
import com.shifa.oms.quikshipx.OrderShipment;
import com.shifa.oms.statemachine.OrderStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A single row in the packing work queues ({@code GET /api/packing/queue}).
 *
 * <p>Carries the order date, the salesperson name (resolved from
 * {@code created_by}), the packaging note, and — for a COURIER (QuikShipX) order —
 * the QuikShipX shipment's label details so the "Orders to Pack" queue can offer a
 * "Print QuikShip label" action and show whether that label has been printed yet
 * (packing-workflow redesign). An IN-HOUSE order carries null label fields.
 *
 * @param id              order id
 * @param orderCode       order code (the internal label barcode value)
 * @param customerName    customer name
 * @param salespersonName the salesperson who created the order (nullable)
 * @param totalAmount     order total
 * @param createdAt       when the order was created
 * @param orderStatus     current lifecycle status
 * @param paymentStatus   payment classification
 * @param deliveryMethod  QUIKSHIPX (courier partner) or IN_HOUSE — drives which
 *                        label buttons + downstream section apply
 * @param source          where the order originated (SALESPERSON / SHOPIFY / STORE /
 *                        STOREFRONT) — drives the Portal-vs-Shopify queue filter
 * @param notes           the order/packaging note (nullable)
 * @param awb             QuikShipX tracking id / AWB (null for in-house or not yet allotted)
 * @param quikShipXLabelUrl the QuikShipX-hosted shipping-label PDF URL (null when none)
 * @param quikShipXLabelPrinted whether the QuikShipX label has already been printed
 * @param quikShipXStatus the QuikShipX-side status string (e.g. "Tracking ID Assigned"), nullable
 */
public record PackingQueueRow(
        Long id,
        String orderCode,
        String customerName,
        String salespersonName,
        BigDecimal totalAmount,
        LocalDateTime createdAt,
        OrderStatus orderStatus,
        PaymentStatus paymentStatus,
        com.shifa.oms.order.DeliveryMethod deliveryMethod,
        com.shifa.oms.order.OrderSource source,
        String notes,
        String awb,
        String quikShipXLabelUrl,
        boolean quikShipXLabelPrinted,
        String quikShipXStatus
) {

    /** A row with no courier-label details (used by status sections / in-house rows). */
    public static PackingQueueRow from(OrderEntity order, String salespersonName) {
        return from(order, salespersonName, null);
    }

    /** A row enriched with the QuikShipX shipment's label details when present. */
    public static PackingQueueRow from(OrderEntity order, String salespersonName, OrderShipment shipment) {
        return new PackingQueueRow(
                order.getId(),
                order.getOrderCode(),
                order.getCustomerName(),
                salespersonName,
                order.getTotalAmount(),
                order.getCreatedAt(),
                order.getOrderStatus(),
                order.getPaymentStatus(),
                order.getDeliveryMethod(),
                order.getSource(),
                order.getNotes(),
                shipment == null ? null : shipment.getAwb(),
                shipment == null ? null : shipment.getLabelUrl(),
                shipment != null && shipment.isLabelPrinted(),
                shipment == null ? null : shipment.getQuikShipXStatus());
    }
}
