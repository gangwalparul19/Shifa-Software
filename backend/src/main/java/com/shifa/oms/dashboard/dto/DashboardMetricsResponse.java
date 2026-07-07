package com.shifa.oms.dashboard.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * The full payload of {@code GET /api/admin/metrics?period=} (Req 19.1, 19.2,
 * 19.3, 19.4, 19.7): the resolved window, the metric cards, the sales graph with
 * previous-period comparison, and the top performers, all computed over the
 * selected time period.
 */
public record DashboardMetricsResponse(
        String period,
        String bucket,
        LocalDate from,
        LocalDate to,
        MetricCards cards,
        SalesGraph salesGraph,
        TopPerformers topPerformers) {

    /**
     * The metric cards (Req 19.1). Sales/order counts and the status breakdown
     * are windowed to the selected period; the COD-pending and claim-pending
     * totals reflect the current outstanding receivables ledger.
     */
    public record MetricCards(
            BigDecimal totalSales,
            long totalOrders,
            long pendingOrders,
            long packedOrders,
            long dispatchedOrders,
            long deliveredOrders,
            long rtoCount,
            long courierLostCount,
            BigDecimal totalCodPendingFromCourier,
            BigDecimal totalLossClaimPendingFromCourier,
            BigDecimal conversionRate) {
    }

    /**
     * The sales graph (Req 19.4): the current-period series bucketed by
     * {@code bucket}, an index-aligned previous-period comparison series, and the
     * overall previous-period percentage change. {@code changeApplicable} is
     * {@code false} when the previous period had zero sales but the current
     * period did not.
     */
    public record SalesGraph(
            List<SalesPoint> points,
            boolean changeApplicable,
            BigDecimal changePercent) {
    }

    /** A single bucket on the sales graph: its label, current sales, and previous-period sales. */
    public record SalesPoint(String label, BigDecimal sales, BigDecimal previousSales) {
    }

    /**
     * Top performers within the selected window (Req 19.7): the top salesperson
     * (id + display name), the top-selling product, and the top state. Any field
     * may be {@code null} when the window has no qualifying orders.
     */
    public record TopPerformers(
            Long topSalespersonId,
            String topSalespersonName,
            String topProduct,
            String topState) {
    }
}
