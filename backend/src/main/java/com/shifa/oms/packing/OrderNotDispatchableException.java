package com.shifa.oms.packing;

import com.shifa.oms.common.ApiException;
import com.shifa.oms.statemachine.OrderStatus;
import org.springframework.http.HttpStatus;

/**
 * Thrown when a dispatch is requested for an order that is not in
 * {@code HANDED_TO_DELIVERY}, so courier assignment cannot be enqueued
 * (Req 10.1). Mapped to HTTP 409 with the stable code
 * {@code ORDER_NOT_DISPATCHABLE}; the current {@link OrderStatus} is exposed via
 * {@link #getCurrentStatus()} (and echoed in the message) so the packer sees
 * exactly why the action was rejected.
 */
public class OrderNotDispatchableException extends ApiException {

    private final transient OrderStatus currentStatus;

    public OrderNotDispatchableException(String orderCode, OrderStatus currentStatus) {
        super(HttpStatus.CONFLICT, "ORDER_NOT_DISPATCHABLE",
                "Order '" + orderCode + "' cannot be dispatched because its current status is "
                        + currentStatus + " (expected HANDED_TO_DELIVERY).");
        this.currentStatus = currentStatus;
    }

    /** The order's current status at the time the dispatch was rejected. */
    public OrderStatus getCurrentStatus() {
        return currentStatus;
    }
}
