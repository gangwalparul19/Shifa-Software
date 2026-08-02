package com.shifa.oms.order;

/**
 * Decides, at approval time, whether an external courier owns this order's fulfilment.
 *
 * <p>A dependency inversion: {@code order} declares what it needs, and
 * {@code integration.quikshipx} implements it. Without this, {@code AdminOrderService}
 * would have to import the QuikShipX module, and the label and packing modules would be
 * dragged into the integration's dependency graph.
 *
 * <p>Injected as {@code @Nullable}: when the integration module is absent or the
 * integration is disabled there is no implementation, and approval keeps generating the
 * internal Code128 label exactly as it did before this feature.
 */
public interface OrderFulfilmentPublisher {

    /**
     * Called immediately after an order transitions to {@code APPROVED}, inside the same
     * transaction.
     *
     * <p>Implementations may enable {@link OrderEntity#setFallbackMode(boolean)} on the
     * order when the integration is disabled, so the order stays on the internal
     * fulfilment path for good rather than being ambiguous later (Req 5.8).
     *
     * @return {@code true} when external publication has been queued, meaning the caller
     *         must NOT generate the internal label — QuikShipX will produce it, and two
     *         labels on one parcel is worse than none
     */
    boolean publishOnApproval(OrderEntity order);
}
