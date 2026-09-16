package com.shifa.oms.order;

/**
 * How an order is fulfilled for last-mile delivery, distinct from
 * {@link OrderSource} (who punched the order) and {@link LeadSource} (the lead's
 * origin channel).
 *
 * <p>By default every order goes through the QuikShipX courier integration when
 * it is enabled ({@code app.quikshipx.enabled}). {@link #IN_HOUSE} lets a
 * salesperson/admin flag a specific order to skip QuikShipX entirely and be
 * delivered by Shifa's own team instead — e.g. a local/same-day delivery with no
 * courier partner involved. Mapped to {@code orders.delivery_method} (V60).
 *
 * <p>Gating: {@code OrderService.publishToQuikShipX} and
 * {@code AdminOrderService.approve}'s confirm-enqueue both check
 * {@code deliveryMethod == QUIKSHIPX} in addition to the existing
 * {@code app.quikshipx.enabled} flag, so an {@link #IN_HOUSE} order never gets an
 * {@code OrderShipment} row and is invisible to the whole QuikShipX pipeline.
 * {@code QuikShipXCourierClient} falls back to the mock/manual courier client at
 * dispatch when no shipment exists, so in-house orders are never blocked there
 * either. In-house orders are advanced to {@code Delivered} (and, for COD,
 * straight to {@code COD_Collected}) via a single manual "Mark delivered" action
 * ({@code OrderController#markDelivered}) rather than the SYSTEM-only courier
 * webhook/poll progressions.
 */
public enum DeliveryMethod {
    /** Fulfilled via the QuikShipX courier integration (default). */
    QUIKSHIPX,
    /** Fulfilled by Shifa's own in-house/last-mile delivery — no courier partner. */
    IN_HOUSE
}
