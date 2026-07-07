package com.shifa.oms.courier.dto;

/**
 * Inbound courier tracking webhook payload (Req 13.1, 13.2). The courier posts
 * the AWB and its raw status token; an optional {@code eventId} lets the courier
 * de-duplicate on its side. Mapping to an internal status and legality checks are
 * done server-side.
 *
 * @param eventId   optional courier-side event id (for logging/traceability)
 * @param awb       the AWB the update concerns (required)
 * @param status    the courier's raw status token, e.g. {@code out_for_delivery} (required)
 */
public record CourierWebhookRequest(String eventId, String awb, String status) {
}
