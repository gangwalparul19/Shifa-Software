package com.shifa.oms.order;

import com.shifa.oms.common.ApiException;
import com.shifa.oms.statemachine.OrderStatus;
import org.springframework.http.HttpStatus;

/**
 * Thrown when an admin attempts to edit an order's details (customer, address,
 * items, discount, notes) after it has moved past the point where a correction
 * is safe — once a label has been generated / the order has begun physical
 * fulfilment (packed, handed to a courier, etc.), the printed label and any
 * courier/QuikShipX payload already reflect the original details, and stock has
 * been reserved against the original line items.
 *
 * <p>Mapped to HTTP 409 with the stable code {@code ORDER_NOT_EDITABLE}; the
 * order is left completely unchanged.
 */
public class OrderNotEditableException extends ApiException {

    private final transient OrderStatus currentStatus;

    public OrderNotEditableException(String orderCode, OrderStatus currentStatus) {
        super(HttpStatus.CONFLICT, "ORDER_NOT_EDITABLE",
                "Order '" + orderCode + "' can no longer be edited because its current status is "
                        + currentStatus + " (editing is only allowed before packing begins).");
        this.currentStatus = currentStatus;
    }

    /** The order's current status at the time the edit was rejected. */
    public OrderStatus getCurrentStatus() {
        return currentStatus;
    }
}
