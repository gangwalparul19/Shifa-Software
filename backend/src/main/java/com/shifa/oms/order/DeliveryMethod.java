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
 *
 * <p><strong>Courier gating</strong>: only {@link #QUIKSHIPX} is a courier
 * order. Every "publish to QuikShipX / assign courier" gate keys off
 * {@code OrderEntity#usesCourier()} ({@code deliveryMethod == QUIKSHIPX}), so
 * {@link #IN_HOUSE} and {@link #STORE_PICKUP} are both invisible to the QuikShipX
 * pipeline. {@link OrderEntity#isInHouseDelivery()} remains {@code IN_HOUSE}-only
 * (used by the manual in-house delivery flow), so it must <em>not</em> be used
 * (negated) as an "is-courier" test.
 */
public enum DeliveryMethod {
    /** Fulfilled via the QuikShipX courier integration (default). */
    QUIKSHIPX,
    /** Fulfilled by Shifa's own in-house/last-mile delivery — no courier partner. */
    IN_HOUSE,
    /**
     * An in-shop / counter (POS) sale handed over to the customer at the store —
     * no delivery partner at all (neither QuikShipX nor in-house). A
     * {@code STORE_PICKUP} order is paid &amp; collected at the counter and must
     * NEVER enter any delivery / packing / dispatch queue; it is created already
     * closed by {@code OrderService#createStoreOrder}. Set server-side only for
     * POS orders — an admin cannot choose it via approve / update-delivery-method.
     */
    STORE_PICKUP
}
