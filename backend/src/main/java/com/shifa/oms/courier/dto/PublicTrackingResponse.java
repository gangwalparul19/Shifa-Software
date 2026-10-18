package com.shifa.oms.courier.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * Customer-facing public tracking view for the token-gated endpoint
 * {@code GET /api/track/t/{token}} (ENHANCEMENT 2.2).
 *
 * <p>Deliberately customer-friendly and minimal: no internal ids, no money, no
 * salesperson — just the order code, a plain-English status, the shipment
 * tracking info once available, and a simple stage timeline. Resolved by an
 * opaque token so it exposes exactly one order and nothing can be enumerated.
 *
 * @param orderCode        the order's code (shown so the customer can quote it)
 * @param customerName     the customer's name for a friendly greeting (may be null)
 * @param statusLabel      a plain-English current status (e.g. "Out for delivery")
 * @param stage            the coarse lifecycle stage key (PENDING_APPROVAL … DELIVERED …)
 * @param awb              the tracking number once assigned, or null
 * @param courierName      the courier/partner name, or null
 * @param trackingUrl      the courier's own tracking link, or null
 * @param estimatedDelivery the estimated delivery date, or null
 * @param delivered        whether the order has been delivered/closed
 * @param timeline         ordered stage steps with a reached/pending flag
 * @param events           the detailed courier scan timeline (newest first; may be empty)
 */
public record PublicTrackingResponse(
        String orderCode,
        String customerName,
        String statusLabel,
        String stage,
        String awb,
        String courierName,
        String trackingUrl,
        LocalDate estimatedDelivery,
        boolean delivered,
        List<Step> timeline,
        List<Event> events
) {

    /**
     * One step on the customer tracking timeline.
     *
     * @param label   the step label ("Order placed", "Shipped", …)
     * @param reached whether the order has reached (or passed) this step
     */
    public record Step(String label, boolean reached) {
    }

    /**
     * One detailed courier scan event, mirrored from QuikShipX's
     * {@code shipment_scanning} and stored in {@code shipment_tracking_events}
     * (Option B). Customer-safe: a status, the free-text movement instruction,
     * the facility/location, and the scan timestamp — no money/ids.
     *
     * @param status       the scan status (e.g. "In Transit", "Delivered")
     * @param instructions the movement instruction (e.g. "Out for delivery")
     * @param location     the facility/location (e.g. "Indore_Dakachya_GW (Madhya Pradesh)")
     * @param scanAt       the raw courier scan timestamp string
     */
    public record Event(String status, String instructions, String location, String scanAt) {
    }
}
