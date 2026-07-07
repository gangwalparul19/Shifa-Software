package com.shifa.oms.courier;

/**
 * A single courier tracking update, from a webhook callback or a poll (Req 13.1,
 * 13.2). Carries the AWB it concerns and the courier's raw status string, which
 * {@link CourierStatusMapper} maps to an internal {@code Order_Status}.
 *
 * @param awb       the AWB the update concerns (never blank)
 * @param rawStatus the courier's raw status token (e.g. {@code out_for_delivery})
 */
public record CourierTrackingEvent(String awb, String rawStatus) {
}
