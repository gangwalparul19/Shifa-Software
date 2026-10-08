package com.shifa.oms.packing.dto;

import java.util.List;

/**
 * The packing team's work queues + shipment-status sections, returned by
 * {@code GET /api/packing/queue} (packing-workflow redesign).
 *
 * <p>Newest-first. The flow is: approve → <b>Orders to Pack</b> ({@code
 * Label_Generated}; both QuikShipX and in-house, with per-row label actions) →
 * <b>Awaiting Handover</b> ({@code Packed}) → handover. After handover an order
 * leaves the packing queues and appears, read-only, in one of the two status
 * sections depending on its delivery partner:
 * <ul>
 *   <li>{@code ordersToPack} — orders in {@code Label_Generated}: label produced
 *       (and, for QuikShipX, a tracking id + courier label allotted) and ready to
 *       be packed;</li>
 *   <li>{@code awaitingHandover} — orders in {@code Packed}: ready to hand to the
 *       courier / in-house driver;</li>
 *   <li>{@code quikShipStatus} — handed-over COURIER orders
 *       ({@code Handed_To_Delivery} / {@code Courier_Assigned} / {@code Dispatched}
 *       / {@code In_Transit} / {@code Out_For_Delivery}): QuikShipX pickup + tracking
 *       drives these automatically (read-only);</li>
 *   <li>{@code inHouseDeliveries} — handed-over IN-HOUSE orders (same status range):
 *       the team advances these manually.</li>
 * </ul>
 * The old separate "awaiting dispatch" queue was removed — handover is the single
 * step out of the packing queues.
 */
public record PackingQueueResponse(
        List<PackingQueueRow> ordersToPack,
        List<PackingQueueRow> awaitingHandover,
        List<PackingQueueRow> quikShipStatus,
        List<PackingQueueRow> inHouseDeliveries
) {
}
