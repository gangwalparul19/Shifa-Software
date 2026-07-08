package com.shifa.oms.insights.domain;

/**
 * A courier company's terminal-shipment outcome counts over the window (design
 * &sect;Pure domain; Req 6.1–6.3), the input to the per-courier scorecard. The
 * four counts partition the courier's terminal shipments.
 *
 * @param courierCompanyId the courier company id ({@code scopeRefId} of the insight)
 * @param courierName      the courier name, for the insight label
 * @param delivered        shipments delivered successfully
 * @param rto              shipments returned to origin
 * @param failed           shipments that failed delivery
 * @param otherTerminal    any other terminal outcome (e.g. lost)
 * @param avgTransitDays   the average transit time in days over the window
 */
public record CourierOutcome(
        Long courierCompanyId,
        String courierName,
        long delivered,
        long rto,
        long failed,
        long otherTerminal,
        double avgTransitDays) {
}
