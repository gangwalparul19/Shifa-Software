package com.shifa.oms.order.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * The ADMIN-only delivery-partner dashboard payload
 * ({@code GET /api/admin/orders/delivery-partner-summary}): order metrics split
 * by the three fulfilment partners so an admin can tell at a glance how each is
 * performing — in transit, delivered, cancelled, COD still to collect, etc.
 *
 * <p>The three partners partition every order exactly once:
 * <ul>
 *   <li><b>QuikShipX</b> = {@code deliveryMethod = QUIKSHIPX} (courier);</li>
 *   <li><b>POS / Store</b> = {@code source = STORE} (in-shop counter sale);</li>
 *   <li><b>In-house (Ishika Enterprise)</b> = everything else (own last-mile
 *       delivery, i.e. {@code deliveryMethod = IN_HOUSE} and not a store sale).</li>
 * </ul>
 *
 * @param from       the window start (yyyy-MM-dd) applied, or null for all-time
 * @param to         the window end (yyyy-MM-dd) applied, or null for all-time
 * @param total      metrics across every order in the window
 * @param quikShipX  metrics for QuikShipX (courier) orders
 * @param inHouse    metrics for in-house (Ishika Enterprise) delivered orders
 * @param pos        metrics for in-shop (POS / counter) store sales
 */
public record DeliveryPartnerSummaryResponse(
        String from,
        String to,
        PartnerStats total,
        PartnerStats quikShipX,
        PartnerStats inHouse,
        PartnerStats pos) {

    /**
     * Metrics for one delivery partner over the window.
     *
     * @param partner         the partner key (QUIKSHIPX / IN_HOUSE / POS / TOTAL)
     * @param label           a human label for the partner
     * @param orderCount      number of orders in the window for this partner
     * @param revenue         total sales for the window, EXCLUDING rejected/cancelled orders
     * @param codToCollect    amount still to collect on delivery (customer_outstanding) for active orders
     * @param pending         orders awaiting approval (PENDING_APPROVAL group)
     * @param processing      orders being prepared (PROCESSING group)
     * @param inTransit       orders out for delivery / shipped (SHIPPED group)
     * @param delivered       orders delivered (DELIVERED group)
     * @param cancelled       orders cancelled (CANCELLED group)
     * @param failedReturned  orders failed or returned (FAILED_RETURNED group)
     * @param monthOrderCount orders THIS calendar month for this partner (independent of the window)
     * @param monthRevenue    this-month sales (excl. rejected/cancelled)
     * @param statusBreakdown count per business lifecycle stage within the window
     */
    public record PartnerStats(
            String partner,
            String label,
            long orderCount,
            BigDecimal revenue,
            BigDecimal codToCollect,
            long pending,
            long processing,
            long inTransit,
            long delivered,
            long cancelled,
            long failedReturned,
            long monthOrderCount,
            BigDecimal monthRevenue,
            List<ChannelSummaryResponse.StatusCount> statusBreakdown) {
    }
}
