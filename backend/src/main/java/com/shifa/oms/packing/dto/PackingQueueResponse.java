package com.shifa.oms.packing.dto;

import com.shifa.oms.order.dto.OrderSummaryResponse;

import java.util.List;

/**
 * The packing team's work queues (Req 9, 10, 11), returned by
 * {@code GET /api/packing/queue}.
 *
 * <p>Each list is oldest-first (FIFO) so the packer clears the earliest orders
 * first:
 * <ul>
 *   <li>{@code awaitingPacking} — orders in {@code Label_Generated}: the label
 *       (with the scannable barcode) has been produced and the order is ready to
 *       be packed;</li>
 *   <li>{@code awaitingHandover} — orders in {@code Packed}: ready to hand over
 *       to the delivery courier;</li>
 *   <li>{@code awaitingDispatch} — orders in {@code Handed_To_Delivery}: ready to
 *       dispatch (enqueue courier assignment).</li>
 * </ul>
 */
public record PackingQueueResponse(
        List<OrderSummaryResponse> awaitingPacking,
        List<OrderSummaryResponse> awaitingHandover,
        List<OrderSummaryResponse> awaitingDispatch
) {
}
