package com.shifa.oms.order;

/**
 * Answers whether an external courier currently owns an order's fulfilment.
 *
 * <p>A dependency inversion, like {@link OrderFulfilmentPublisher}: {@code order}
 * declares the question, {@code integration.quikshipx} answers it. That keeps the
 * order module — and the packing and label modules that depend on it — free of any
 * compile-time knowledge of the courier integration.
 *
 * <p>Consulted on every status transition and on every packing-queue read, so
 * implementations must be cheap: an existence check, not an aggregate load.
 */
public interface OrderFulfilmentOwnership {

    /**
     * Whether an external courier owns this order's fulfilment: a shipment record
     * exists AND fallback mode is off.
     *
     * <p>Implementations fold fallback mode in here so callers reason about a single
     * boolean and cannot accidentally treat a taken-back order as managed.
     */
    boolean isCourierManaged(OrderEntity order);
}
