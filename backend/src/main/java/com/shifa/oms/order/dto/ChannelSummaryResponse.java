package com.shifa.oms.order.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * The ADMIN-only channel dashboard payload ({@code GET /api/admin/orders/channel-summary}):
 * order metrics split by origin channel so an admin can track and compare the
 * business's own portal orders (punched by salespeople / storefront) against the
 * auto-imported Shopify orders, plus the combined total, all within a chosen
 * date window.
 *
 * @param from       the window start (yyyy-MM-dd) applied, or null for all-time
 * @param to         the window end (yyyy-MM-dd) applied, or null for all-time
 * @param total      metrics across every order in the window
 * @param portal     metrics for orders punched online by our team (SALESPERSON /
 *                   STOREFRONT) — i.e. neither Shopify nor an in-shop store sale
 * @param shopify    metrics for orders auto-imported from Shopify
 * @param store      metrics for in-shop (POS / counter) store sales
 */
public record ChannelSummaryResponse(
        String from,
        String to,
        ChannelStats total,
        ChannelStats portal,
        ChannelStats shopify,
        ChannelStats store) {

    /**
     * Metrics for one channel over the window.
     *
     * @param orderCount        number of orders in the window for this channel
     * @param revenue           total sales for the window, EXCLUDING rejected/cancelled orders
     * @param codOutstanding    amount still to collect on delivery (customer_outstanding) for active orders
     * @param monthOrderCount   orders THIS calendar month for this channel (independent of the window)
     * @param monthRevenue      this-month sales (excl. rejected/cancelled)
     * @param statusBreakdown   count per business lifecycle stage within the window
     */
    public record ChannelStats(
            long orderCount,
            BigDecimal revenue,
            BigDecimal codOutstanding,
            long monthOrderCount,
            BigDecimal monthRevenue,
            List<StatusCount> statusBreakdown) {
    }

    /**
     * A single lifecycle-stage tally for the status breakdown.
     *
     * @param group the {@link com.shifa.oms.order.OrderStatusGroup} key (e.g. PENDING_APPROVAL)
     * @param label a human label for the stage
     * @param count number of orders in this stage for the channel + window
     */
    public record StatusCount(String group, String label, long count) {
    }
}
