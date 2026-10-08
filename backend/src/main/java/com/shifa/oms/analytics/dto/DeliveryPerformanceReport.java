package com.shifa.oms.analytics.dto;

import java.util.List;

/**
 * Delivery-performance analytics (ENHANCEMENT 3.3): delivered-vs-failed rates
 * sliced by courier, destination state, and pincode band, so the owner can spot
 * high-RTO zones (switch them to prepaid-only) and underperforming couriers.
 * Read-only aggregate over orders that reached a terminal delivery outcome
 * (delivered ∪ failed).
 *
 * @param overall  the business-wide delivered/failed totals + success rate
 * @param byCourier per-courier rows, worst success rate first
 * @param byState   per-state rows, worst success rate first
 * @param byPincode per-pincode-band rows, worst success rate first
 */
public record DeliveryPerformanceReport(
        Row overall,
        List<Row> byCourier,
        List<Row> byState,
        List<Row> byPincode
) {

    /**
     * One delivery-performance row.
     *
     * @param dimension   the group label (courier name / state / pincode band), or "Overall"
     * @param delivered   successfully delivered count
     * @param failed      failed/returned count (CUSTOMER_REJECTED/DELIVERY_FAILED/RTO/REDISPATCH)
     * @param total       delivered + failed
     * @param successRate delivered / total as a 0–100 percentage (1 dp)
     */
    public record Row(String dimension, long delivered, long failed, long total, double successRate) {
    }
}
