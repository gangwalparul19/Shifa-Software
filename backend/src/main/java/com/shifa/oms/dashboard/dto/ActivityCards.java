package com.shifa.oms.dashboard.dto;

/**
 * The dashboard activity-card counts (Req 19.6): work waiting across the
 * pipeline. Pushed over SSE alongside {@link LiveStats} and available on demand.
 *
 * <ul>
 *   <li>{@code ordersToFulfill} — approved/label-generated/packed orders awaiting dispatch;</li>
 *   <li>{@code paymentsToCapture} — orders pending admin approval (payment review);</li>
 *   <li>{@code rtoAlerts} — orders returned to origin;</li>
 *   <li>{@code whatsappNotificationsSent} — WhatsApp messages successfully delivered;</li>
 *   <li>{@code codSettlementsPending} — unsettled COD receivables;</li>
 *   <li>{@code courierClaimsPending} — unsettled loss claims to file.</li>
 * </ul>
 */
public record ActivityCards(
        long ordersToFulfill,
        long paymentsToCapture,
        long rtoAlerts,
        long whatsappNotificationsSent,
        long codSettlementsPending,
        long courierClaimsPending) {
}
