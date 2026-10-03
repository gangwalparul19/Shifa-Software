package com.shifa.oms.dashboard.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * The channel-aware admin dashboard ({@code GET /api/admin/dashboard/channel}).
 *
 * <p>Every section is scoped to the requested {@code channel} (ALL / PORTAL /
 * SHOPIFY) except {@link #split}, which always carries all three side by side so
 * the admin can compare channels. Revenue everywhere EXCLUDES rejected,
 * payment-rejected and cancelled orders (the same rule as the Channel summary and
 * reports); order counts include every order placed in the window.
 *
 * <p>Nullable fields: percentage changes are {@code null} when there is no
 * previous-period value to compare against; {@code deliverySuccessPct} is
 * {@code null} when no order has reached a final delivery outcome yet;
 * {@code queues.portal} / {@code queues.shopify} are {@code null} when that side
 * is out of the requested channel.
 */
public record ChannelDashboardResponse(
        String channel,
        String period,
        String bucket,
        LocalDate from,
        LocalDate to,
        ChannelSplit split,
        Kpis kpis,
        List<TrendPoint> trend,
        List<StageCount> pipeline,
        Queues queues,
        Payments payments,
        Performance performance) {

    /** Portal vs Shopify vs combined totals for the window (always all three). */
    public record ChannelSplit(ChannelTotals all, ChannelTotals portal, ChannelTotals shopify) {
    }

    /** One channel's headline totals, with the previous-period comparison. */
    public record ChannelTotals(
            long orders,
            BigDecimal revenue,
            BigDecimal avgOrderValue,
            BigDecimal revenueSharePct,
            long previousOrders,
            BigDecimal previousRevenue,
            BigDecimal revenueChangePct,
            BigDecimal ordersChangePct) {
    }

    /** Headline KPIs for the selected channel. */
    public record Kpis(
            long orders,
            BigDecimal revenue,
            BigDecimal avgOrderValue,
            long delivered,
            long inProgress,
            long failedReturned,
            long cancelledRejected,
            BigDecimal deliverySuccessPct,
            BigDecimal codToCollect,
            long previousOrders,
            BigDecimal previousRevenue,
            BigDecimal revenueChangePct,
            BigDecimal ordersChangePct) {
    }

    /** One revenue-trend bucket, split by channel, with the previous period's total. */
    public record TrendPoint(
            String label,
            BigDecimal portal,
            BigDecimal shopify,
            BigDecimal total,
            BigDecimal previousTotal) {
    }

    /** One lifecycle stage (every stage is listed, so the counts add up to {@code kpis.orders}). */
    public record StageCount(String group, String label, long count) {
    }

    /** Live work queues (current snapshot — not limited to the period). */
    public record Queues(PortalQueues portal, ShopifyQueues shopify) {
    }

    /** Portal fulfilment steps our team performs by hand. */
    public record PortalQueues(long pendingApproval, long toPack, long awaitingHandover, long awaitingDispatch) {
    }

    /** Shopify fulfilment steps: stuck before QuikShipX, then print label, then courier pickup. */
    public record ShopifyQueues(long stuck, long labelsToPrint, long awaitingPickup) {
    }

    /** Payment mix and cash position for the selected channel. */
    public record Payments(
            List<PaymentMix> mix,
            BigDecimal collectedByTeam,
            BigDecimal paidOnShopify,
            BigDecimal codToCollect,
            BigDecimal codPendingFromCourier,
            BigDecimal lossClaimPending,
            long todayOrders,
            BigDecimal todayCollectedByTeam,
            BigDecimal todayPaidOnShopify) {
    }

    /** Orders and value per payment type (prepaid / partial / pay on delivery). */
    public record PaymentMix(String status, String label, long orders, BigDecimal amount) {
    }

    /** Leaderboards for the selected channel. */
    public record Performance(List<RankRow> topSalespeople, List<RankRow> topProducts, List<RankRow> topStates) {
    }

    /** One leaderboard row: name, a count (orders or units) and revenue. */
    public record RankRow(String name, long count, BigDecimal revenue) {
    }
}
