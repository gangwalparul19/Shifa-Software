package com.shifa.oms.packing;

import com.shifa.oms.common.ApiException;
import com.shifa.oms.statemachine.OrderStatus;
import org.springframework.http.HttpStatus;

/**
 * Thrown when a scanned order exists but is not in {@code Label_Generated}, so it
 * cannot be packed (Req 11.4). Mapped to HTTP 409 with the stable code
 * {@code ORDER_NOT_PACKABLE}; the current {@link OrderStatus} is exposed via
 * {@link #getCurrentStatus()} (and echoed in the message) so the packer sees
 * exactly why the scan was rejected.
 */
public class OrderNotPackableException extends ApiException {

    private final transient OrderStatus currentStatus;

    public OrderNotPackableException(String orderCode, OrderStatus currentStatus) {
        super(HttpStatus.CONFLICT, "ORDER_NOT_PACKABLE",
                "Order '" + orderCode + "' cannot be packed because its current status is "
                        + currentStatus + " (expected LABEL_GENERATED).");
        this.currentStatus = currentStatus;
    }

    /** The order's current status at the time the scan was rejected (Req 11.4). */
    public OrderStatus getCurrentStatus() {
        return currentStatus;
    }
}
