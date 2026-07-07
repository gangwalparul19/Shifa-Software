package com.shifa.oms.packing.dto;

import com.shifa.oms.order.OrderEntity;
import com.shifa.oms.order.dto.OrderSummaryResponse;

/**
 * Success response for a packing scan that moved an order to {@code Packed}
 * (Req 11.1). Carries a human-readable confirmation message plus a compact
 * summary of the packed order so the packing UI can show which order was just
 * confirmed and append it to the session scan log.
 *
 * <p>The unrecognized-barcode (Req 11.3) and wrong-status (Req 11.4) outcomes are
 * <em>not</em> represented here: they are returned as the standard error
 * envelope with HTTP 404 ({@code BARCODE_NOT_RECOGNIZED}) and 409
 * ({@code ORDER_NOT_PACKABLE}) respectively.
 */
public record PackingScanResponse(
        String message,
        OrderSummaryResponse order
) {

    public static PackingScanResponse packed(OrderEntity order) {
        return new PackingScanResponse(
                "Order " + order.getOrderCode() + " marked Packed.",
                OrderSummaryResponse.from(order));
    }
}
