package com.shifa.oms.packing;

import com.shifa.oms.common.ApiException;
import com.shifa.oms.statemachine.OrderStatus;
import org.springframework.http.HttpStatus;

/**
 * Thrown when a handover is requested for an order that is not in {@code PACKED},
 * so it cannot be handed over to the delivery courier (Req 9.4). Mapped to HTTP
 * 409 with the stable code {@code ORDER_NOT_HANDOVERABLE}; the current
 * {@link OrderStatus} is exposed via {@link #getCurrentStatus()} (and echoed in
 * the message) so the packer sees exactly why the action was rejected.
 */
public class OrderNotHandoverableException extends ApiException {

    private final transient OrderStatus currentStatus;

    public OrderNotHandoverableException(String orderCode, OrderStatus currentStatus) {
        super(HttpStatus.CONFLICT, "ORDER_NOT_HANDOVERABLE",
                "Order '" + orderCode + "' cannot be handed over because its current status is "
                        + currentStatus + " (expected PACKED).");
        this.currentStatus = currentStatus;
    }

    /** The order's current status at the time the handover was rejected. */
    public OrderStatus getCurrentStatus() {
        return currentStatus;
    }
}
