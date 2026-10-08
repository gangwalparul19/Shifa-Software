package com.shifa.oms.order.dto;

import com.shifa.oms.statemachine.OrderStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request body for manually advancing an <strong>in-house</strong> delivery's
 * status ({@code POST /api/orders/{id}/delivery-status}, in-house-delivery
 * feature).
 *
 * <p>An in-house order has no courier partner, so no webhook/poll will ever
 * report progress — staff move it through the delivery stages by hand. Only the
 * delivery-stage statuses are accepted (validated in the service against a
 * whitelist); the state machine still rejects an illegal hop from the order's
 * current status with a 409.
 *
 * @param status        the delivery stage to move to (Dispatched / In_Transit /
 *                      Out_For_Delivery / Delivered / Customer_Rejected /
 *                      Delivery_Failed)
 * @param vehicleNumber optional vehicle / transport reference to record or update
 *                      at the same time (bus vehicle no., train no., own van, …)
 * @param note          optional free-text note about this step
 */
public record UpdateDeliveryStatusRequest(
        @NotNull(message = "status is required")
        OrderStatus status,

        @Size(max = 40, message = "vehicleNumber must be at most 40 characters")
        String vehicleNumber,

        @Size(max = 500, message = "note must be at most 500 characters")
        String note
) {
}
