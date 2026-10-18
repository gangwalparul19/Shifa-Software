package com.shifa.oms.courier.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * Privacy-safe list of a customer's orders for the public self-service tracking
 * page {@code /track-order} ({@code GET /api/track/by-mobile?mobile=...}).
 *
 * <p>A customer (or an agent) looks up their orders by the registered mobile
 * number — no login — and picks one to open its live tracking via the existing
 * token-gated {@code GET /api/track/t/{token}} endpoint. Each card is
 * deliberately minimal: it exposes <em>only</em> what the tracking list needs.
 * There is NO money, NO address, NO mobile echo, NO internal id and NO
 * salesperson here, so the public lookup can never leak sensitive data.
 *
 * @param brandName the business/brand display name from settings (so the public
 *                  tracking page shows the configured name, not a hardcoded one)
 * @param orders    the customer's orders as tracking cards, newest first (possibly empty)
 */
public record TrackByMobileResponse(String brandName, List<OrderCard> orders) {

    /**
     * One order on the by-mobile tracking list.
     *
     * @param orderCode     the order's code (shown so the customer recognises it)
     * @param trackingToken the opaque per-order token used to open the live
     *                      tracking detail via {@code GET /api/track/t/{token}}
     * @param productSummary a short "Amla Juice x2, Neem Caps x1" item summary
     * @param statusLabel   a plain-English current status (same mapping as the detail)
     * @param stage         the coarse lifecycle stage key (PENDING_APPROVAL … DELIVERED …)
     * @param delivered     whether the order has been delivered/closed
     * @param placedOn      the date the order was placed
     */
    public record OrderCard(
            String orderCode,
            String trackingToken,
            String productSummary,
            String statusLabel,
            String stage,
            boolean delivered,
            LocalDate placedOn
    ) {
    }
}
