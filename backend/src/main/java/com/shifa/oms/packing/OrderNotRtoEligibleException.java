package com.shifa.oms.packing;

import com.shifa.oms.common.ApiException;
import com.shifa.oms.statemachine.OrderStatus;
import org.springframework.http.HttpStatus;

/**
 * Thrown when a scanned order exists but is not in a status from which RTO is a
 * legal manual move (label redesign feature) — i.e. not one of
 * {@code COURIER_ASSIGNED}/{@code DISPATCHED}/{@code IN_TRANSIT}/
 * {@code OUT_FOR_DELIVERY}. Mapped to HTTP 409 with the stable code
 * {@code ORDER_NOT_RTO_ELIGIBLE}; the current {@link OrderStatus} is exposed via
 * {@link #getCurrentStatus()} (and echoed in the message) so the packer sees
 * exactly why the scan was rejected.
 */
public class OrderNotRtoEligibleException extends ApiException {

    private final transient OrderStatus currentStatus;

    public OrderNotRtoEligibleException(String orderCode, OrderStatus currentStatus) {
        super(HttpStatus.CONFLICT, "ORDER_NOT_RTO_ELIGIBLE",
                "Order '" + orderCode + "' cannot be marked RTO because its current status is "
                        + currentStatus + " (must be in transit to a courier).");
        this.currentStatus = currentStatus;
    }

    /** The order's current status at the time the RTO scan was rejected. */
    public OrderStatus getCurrentStatus() {
        return currentStatus;
    }
}
