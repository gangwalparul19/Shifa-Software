package com.shifa.oms.analytics.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Read-model DTOs for the growth analytics served from {@code /api/admin/analytics/*}
 * (overview, customers, products, operations, growth pointers and the dataset catalog).
 *
 * <p>Conventions: money is a {@link BigDecimal} rounded to 2 places; percentages are
 * plain numbers on a 0-100 scale rounded to 1 place; "revenue" always excludes
 * rejected, payment-rejected and cancelled orders, while "lost orders" counts exactly
 * those.
 */
public final class GrowthAnalytics {

    private GrowthAnalytics() {
    }

    // --- Overview ---------------------------------------------------------

    /**
     * Headline numbers for one period.
     *
     * @param orders                revenue orders (excludes rejected / payment-rejected / cancelled)
     * @param customers             distinct customers (by mobile) who ordered in the period
     * @param newCustomers          customers whose first-ever order falls in the period
     * @param returningCustomers    customers who had ordered before the period started
     * @param returningRevenue      revenue from the returning customers
     * @param discountedOrdersPct   share of revenue orders that carried a discount
     * @param lostOrders            rejected + payment-rejected + cancelled orders in the period
     * @param lostOrdersPct         lost orders as a share of all orders placed in the period
     */
    public record Kpis(
            long orders,
            BigDecimal revenue,
            BigDecimal avgOrderValue,
            long customers,
            long newCustomers,
            long returningCustomers,
            BigDecimal returningRevenue,
            double returningRevenueSharePct,
            BigDecimal discountTotal,
            double discountedOrdersPct,
            long lostOrders,
            double lostOrdersPct) {
    }

    /** One bucket of the sales trend: a day, or the Monday of a week for long ranges. */
    public record TrendPoint(LocalDate date, long orders, BigDecimal revenue, long newCustomers) {
    }

    /**
     * One slice of a mix table (channel, lead source or payment type).
     *
     * @param successRatePct delivered / (delivered + failed) for orders that reached a delivery
     *                       outcome, or {@code null} when none has yet
     */
    public record MixRow(
            String key,
            String label,
            long orders,
            BigDecimal revenue,
            double revenueSharePct,
            BigDecimal avgOrderValue,
            Double successRatePct) {
    }

    /** One destination (state or city). */
    public record GeoRow(
            String name,
            long orders,
            BigDecimal revenue,
            double revenueSharePct,
            long customers,
            BigDecimal avgOrderValue) {
    }

    /**
     * Orders by weekday and hour of day (IST).
     *
     * @param cells      7 rows (Monday first) of 24 hourly order counts
     * @param byWeekday  order totals per weekday, Monday first
     * @param byHour     order totals per hour, 0-23
     * @param peakDay    weekday index (0 = Monday) with the most orders, or -1 when there are none
     * @param peakHour   hour (0-23) with the most orders, or -1 when there are none
     */
    public record HeatMap(
            List<List<Long>> cells,
            List<Long> byWeekday,
            List<Long> byHour,
            int peakDay,
            int peakHour,
            long maxCell) {
    }

    /** The overview tab: the selected period against the previous one of the same length. */
    public record OverviewReport(
            LocalDate from,
            LocalDate to,
            LocalDate previousFrom,
            LocalDate previousTo,
            Kpis current,
            Kpis previous,
            String trendBucket,
            List<TrendPoint> trend,
            List<MixRow> channelMix,
            List<MixRow> leadSourceMix,
            List<MixRow> paymentMix,
            List<GeoRow> topStates,
            List<GeoRow> topCities,
            HeatMap heatmap) {
    }

    // --- Customers --------------------------------------------------------

    /** A customer in a list. */
    public record CustomerRow(
            String name,
            String mobile,
            long orders,
            BigDecimal revenue,
            LocalDate lastOrderDate,
            long daysSinceLastOrder) {
    }

    /**
     * An engagement segment.
     *
     * @param action       what the team should do with these customers
     * @param topCustomers the highest-value customers in the segment (a ready call list)
     */
    public record SegmentRow(
            String key,
            String label,
            String description,
            String action,
            long customers,
            double customerSharePct,
            BigDecimal revenue,
            double revenueSharePct,
            double avgOrders,
            double avgRecencyDays,
            List<CustomerRow> topCustomers) {
    }

    /** A count/revenue band (order frequency or recency). */
    public record BandRow(String label, long customers, BigDecimal revenue) {
    }

    /** The customers tab: RFM-style segments, concentration and recency/frequency spread (all time). */
    public record CustomerSegmentsReport(
            LocalDate asOf,
            long totalCustomers,
            BigDecimal totalRevenue,
            BigDecimal avgLifetimeValue,
            double repeatRatePct,
            double top10PctRevenueSharePct,
            double top20PctRevenueSharePct,
            long atRiskCustomers,
            BigDecimal atRiskRevenue,
            List<SegmentRow> segments,
            List<BandRow> orderFrequency,
            List<BandRow> recencyBands,
            List<CustomerRow> topCustomers) {
    }

    // --- Products ---------------------------------------------------------

    /**
     * One product's performance in the period.
     *
     * @param buyers            distinct customers who bought it
     * @param repeatBuyerPct    share of buyers who bought it in two or more orders in the period
     * @param abcClass          A = the products making up the first 80% of revenue, B = next 15%, C = the rest
     * @param revenueGrowthPct  change in revenue against the previous period, or {@code null} when
     *                          the product had no sales then
     */
    public record ProductRow(
            Long productId,
            String name,
            long units,
            BigDecimal revenue,
            long orders,
            long buyers,
            double repeatBuyerPct,
            double revenueSharePct,
            double cumulativeSharePct,
            String abcClass,
            long previousUnits,
            BigDecimal previousRevenue,
            Double revenueGrowthPct) {
    }

    /**
     * Two products bought in the same order.
     *
     * @param supportPct share of all revenue orders that contain both
     * @param lift       how much more often they are bought together than chance would predict
     */
    public record AffinityPair(String productA, String productB, long orders, double supportPct, double lift) {
    }

    /** The products tab. */
    public record ProductPerformanceReport(
            LocalDate from,
            LocalDate to,
            long totalUnits,
            BigDecimal totalRevenue,
            long productsSold,
            double top3RevenueSharePct,
            long multiProductOrders,
            double multiProductOrdersPct,
            double avgUnitsPerOrder,
            List<ProductRow> products,
            List<AffinityPair> pairs) {
    }

    // --- Operations -------------------------------------------------------

    /** A fulfilment time statistic, in hours. Null figures mean there were no samples. */
    public record SpeedStat(
            String key,
            String label,
            long samples,
            Double medianHours,
            Double p90Hours,
            Double avgHours) {
    }

    /** Orders by lifecycle stage. */
    public record OutcomeRow(String key, String label, long orders, double sharePct) {
    }

    /** Delivery outcome by how the customer paid. */
    public record PaymentRiskRow(
            String key,
            String label,
            long orders,
            long delivered,
            long failed,
            double successRatePct,
            BigDecimal revenue) {
    }

    /** A reason and its order count. */
    public record ReasonRow(String key, String label, long orders) {
    }

    /** The operations tab: how fast orders move, how they end, and why they fail. */
    public record OperationsReport(
            LocalDate from,
            LocalDate to,
            long orders,
            List<SpeedStat> speed,
            List<OutcomeRow> outcomes,
            List<PaymentRiskRow> paymentRisk,
            List<ReasonRow> rejectReasons,
            List<ReasonRow> rtoReasons) {
    }

    // --- Growth pointers --------------------------------------------------

    /**
     * An actionable finding.
     *
     * @param severity    WARNING, OPPORTUNITY, POSITIVE or INFO
     * @param area        the part of the business it concerns (Customers, Products, ...)
     * @param metric      the headline figure behind it, ready to show
     * @param actionRoute an app route that helps act on it, or {@code null}
     */
    public record Pointer(
            String id,
            String severity,
            String area,
            String title,
            String detail,
            String metric,
            String actionLabel,
            String actionRoute) {
    }

    /** The key pointers for a period. */
    public record PointersReport(LocalDate from, LocalDate to, List<Pointer> pointers) {
    }

    // --- Dataset catalog --------------------------------------------------

    /** A column of an exportable dataset. */
    public record ColumnInfo(String name, String type, String description) {
    }

    /** An exportable dataset, documented for people who load it into other tools. */
    public record DatasetInfo(
            String key,
            String label,
            String description,
            String grain,
            boolean usesDateRange,
            List<ColumnInfo> columns) {
    }
}
