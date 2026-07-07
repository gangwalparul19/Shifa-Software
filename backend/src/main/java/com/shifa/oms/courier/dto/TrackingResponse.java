package com.shifa.oms.courier.dto;

import com.shifa.oms.statemachine.OrderStatus;

/**
 * Public order-tracking projection for {@code GET /api/track/{orderCode}}
 * (Req 13.4): the current lifecycle status, the AWB (once assigned), and a
 * courier tracking link built from the courier's URL template. AWB, courier, and
 * link are {@code null} until a courier is assigned.
 *
 * @param orderCode   the order's code
 * @param orderStatus the current lifecycle status
 * @param awb         the assigned AWB, or {@code null}
 * @param courierName the courier company name, or {@code null}
 * @param trackingUrl the courier tracking link, or {@code null}
 */
public record TrackingResponse(
        String orderCode,
        OrderStatus orderStatus,
        String awb,
        String courierName,
        String trackingUrl) {
}
