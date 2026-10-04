package com.shifa.oms.order.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Multi-channel revenue attribution with estimated gross margin (ENHANCEMENT 3.6).
 *
 * <p>Layers product cost (V79 {@code products.cost_price}) onto the per-channel
 * revenue so the owner sees true gross margin per channel (Portal / Shopify /
 * Store), not just revenue. Revenue is the discounted order total (so discount is
 * already reflected); margin = revenue − estimated COGS. Because cost is optional,
 * each channel reports a {@code costCoveragePct} — the share of its line value that
 * had a recorded cost — so a partial-cost channel is never read as a true margin.
 *
 * @param from          window start (ISO date) or null for all-time
 * @param to            window end (ISO date) or null
 * @param channels      per-channel rows (Portal / Shopify / Store), highest revenue first
 * @param total         the all-channels roll-up
 */
public record ChannelMarginResponse(
        String from,
        String to,
        List<ChannelMargin> channels,
        ChannelMargin total
) {

    /**
     * One channel's revenue/cost/margin.
     *
     * @param channel         the channel label (Portal / Shopify / Store / Total)
     * @param orderCount      revenue orders in the window
     * @param revenue         discounted order-total revenue
     * @param discount        total discount given (informational; already in revenue)
     * @param estimatedCogs   Σ(qty × cost_price) over lines whose product has a cost
     * @param grossMargin     revenue − estimatedCogs
     * @param marginPct       grossMargin / revenue as a 0–100 percentage
     * @param costCoveragePct share of line value (by amount) that had a recorded cost (0–100)
     */
    public record ChannelMargin(
            String channel,
            long orderCount,
            BigDecimal revenue,
            BigDecimal discount,
            BigDecimal estimatedCogs,
            BigDecimal grossMargin,
            double marginPct,
            double costCoveragePct
    ) {
    }
}
