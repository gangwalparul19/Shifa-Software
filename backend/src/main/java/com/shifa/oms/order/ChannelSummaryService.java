package com.shifa.oms.order;

import com.shifa.oms.dashboard.ChannelAggregateRepository;
import com.shifa.oms.dashboard.ChannelAggregateRepository.SourceStatusRow;
import com.shifa.oms.order.dto.ChannelSummaryResponse;
import com.shifa.oms.order.dto.ChannelSummaryResponse.ChannelStats;
import com.shifa.oms.order.dto.ChannelSummaryResponse.StatusCount;
import com.shifa.oms.statemachine.OrderStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Computes the ADMIN-only channel dashboard: order metrics split by origin
 * channel (portal vs Shopify vs in-shop store) plus the combined total, over a
 * chosen date window, with a per-channel status breakdown, COD outstanding, and
 * this-month figures.
 *
 * <p>Read-only. The orders are NOT loaded: the database groups them by source
 * and status ({@link ChannelAggregateRepository}) and this service folds those
 * few rows into the channel totals, applying the same money rules used
 * elsewhere: revenue EXCLUDES rejected/cancelled orders, and COD outstanding is
 * the persisted {@code customer_outstanding} on active orders. A Shopify order
 * is {@link OrderSource#SHOPIFY}, a store sale is {@link OrderSource#STORE};
 * "portal" is everything else (SALESPERSON / STOREFRONT).
 */
@Service
public class ChannelSummaryService {

    /** Non-revenue statuses excluded from sales totals (mirrors CustomerInsightService / ReportService). */
    private static final Set<OrderStatus> NON_REVENUE = EnumSet.of(
            OrderStatus.REJECTED, OrderStatus.PAYMENT_REJECTED, OrderStatus.CANCELLED);

    /** Business zone for the "this month" window (IST), matching order-entry rules. */
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Kolkata");

    /** Human labels for the lifecycle groups shown in the breakdown. */
    private static final Map<OrderStatusGroup, String> GROUP_LABELS = new EnumMap<>(OrderStatusGroup.class);

    static {
        GROUP_LABELS.put(OrderStatusGroup.PENDING_APPROVAL, "Pending Approval");
        GROUP_LABELS.put(OrderStatusGroup.PROCESSING, "Processing");
        GROUP_LABELS.put(OrderStatusGroup.SHIPPED, "Shipped");
        GROUP_LABELS.put(OrderStatusGroup.DELIVERED, "Delivered");
        GROUP_LABELS.put(OrderStatusGroup.FAILED_RETURNED, "Failed / Returned");
        GROUP_LABELS.put(OrderStatusGroup.CANCELLED, "Cancelled");
        GROUP_LABELS.put(OrderStatusGroup.REJECTED, "Rejected");
    }

    /** Maps each fine-grained status to its coarse group once, for fast breakdown tallying. */
    private static final Map<OrderStatus, OrderStatusGroup> STATUS_TO_GROUP = buildStatusToGroup();

    private static Map<OrderStatus, OrderStatusGroup> buildStatusToGroup() {
        Map<OrderStatus, OrderStatusGroup> map = new EnumMap<>(OrderStatus.class);
        for (OrderStatusGroup group : OrderStatusGroup.values()) {
            for (OrderStatus status : group.statuses()) {
                map.put(status, group);
            }
        }
        return map;
    }

    private final ChannelAggregateRepository aggregates;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public ChannelSummaryService(ChannelAggregateRepository aggregates) {
        this(aggregates, Clock.system(BUSINESS_ZONE));
    }

    /** Test constructor with a fixed clock (deterministic "this month" window). */
    ChannelSummaryService(ChannelAggregateRepository aggregates, Clock clock) {
        this.aggregates = aggregates;
        this.clock = clock;
    }

    /**
     * Builds the channel summary for the given inclusive date window. A null
     * {@code from}/{@code to} means unbounded on that side (all-time). The
     * this-month figures are always the current calendar month regardless of the
     * window, so an admin sees both the windowed comparison and the live month.
     */
    @Transactional(readOnly = true)
    public ChannelSummaryResponse summary(LocalDate from, LocalDate to) {
        LocalDateTime start = from != null ? from.atStartOfDay() : null;
        LocalDateTime end = to != null ? LocalDateTime.of(to, LocalTime.MAX) : null;

        // "This month" boundaries (IST), independent of the requested window.
        LocalDate today = LocalDate.now(clock);
        LocalDateTime monthStart = today.withDayOfMonth(1).atStartOfDay();
        LocalDateTime monthEnd = today.plusMonths(1).withDayOfMonth(1).atStartOfDay();

        Accumulator total = new Accumulator();
        Accumulator portal = new Accumulator();
        Accumulator shopify = new Accumulator();
        Accumulator store = new Accumulator();

        for (SourceStatusRow row : loadWindow(start, end)) {
            total.addWindow(row);
            channelOf(row, portal, shopify, store).addWindow(row);
        }
        for (SourceStatusRow row : loadMonth(start, end, monthStart, monthEnd)) {
            total.addMonth(row);
            channelOf(row, portal, shopify, store).addMonth(row);
        }

        return new ChannelSummaryResponse(
                from != null ? from.toString() : null,
                to != null ? to.toString() : null,
                total.toStats(),
                portal.toStats(),
                shopify.toStats(),
                store.toStats());
    }

    /**
     * Portal = our own online orders (neither Shopify nor an in-shop store
     * sale), so each order lands in exactly one of the three channels.
     */
    private static Accumulator channelOf(SourceStatusRow row, Accumulator portal,
                                         Accumulator shopify, Accumulator store) {
        return switch (row.getOrderSource()) {
            case SHOPIFY -> shopify;
            case STORE -> store;
            default -> portal;
        };
    }

    /**
     * Groups the orders in the window by source and status. Only the
     * fully-unbounded "all time" case reads every order (that genuinely IS the
     * whole order book); any bounded side pushes the filter to SQL via a wide
     * sentinel on the open side.
     */
    private List<SourceStatusRow> loadWindow(LocalDateTime start, LocalDateTime end) {
        List<SourceStatusRow> rows;
        if (start == null && end == null) {
            rows = aggregates.allTotals();
        } else {
            rows = aggregates.windowTotals(start != null ? start : SENTINEL_MIN, end != null ? end : SENTINEL_MAX);
        }
        return rows == null ? List.of() : rows;
    }

    /**
     * The window's orders created this calendar month, grouped by source and
     * status. A sentinel on an open side is exact here: the month always lies
     * inside it, and a null created-at can never be "this month".
     */
    private List<SourceStatusRow> loadMonth(LocalDateTime start, LocalDateTime end,
                                            LocalDateTime monthStart, LocalDateTime monthEnd) {
        List<SourceStatusRow> rows = aggregates.monthTotals(
                start != null ? start : SENTINEL_MIN, end != null ? end : SENTINEL_MAX, monthStart, monthEnd);
        return rows == null ? List.of() : rows;
    }

    /** Wide sentinels so a one-sided window still runs as a bounded SQL query. */
    private static final LocalDateTime SENTINEL_MIN = LocalDateTime.of(1970, 1, 1, 0, 0);
    private static final LocalDateTime SENTINEL_MAX = LocalDateTime.of(9999, 12, 31, 23, 59, 59);

    private static boolean isRevenue(OrderStatus status) {
        return !NON_REVENUE.contains(status);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }

    private static long nzLong(Long v) {
        return v != null ? v : 0L;
    }

    /** Accumulates per-channel metrics as the grouped source/status rows are folded in. */
    private static final class Accumulator {
        private long orderCount;
        private BigDecimal revenue = BigDecimal.ZERO;
        private BigDecimal codOutstanding = BigDecimal.ZERO;
        private long monthOrderCount;
        private BigDecimal monthRevenue = BigDecimal.ZERO;
        private final Map<OrderStatusGroup, Long> byGroup = new EnumMap<>(OrderStatusGroup.class);

        /** Folds in the orders of one (source, status) group within the window. */
        void addWindow(SourceStatusRow row) {
            long count = nzLong(row.getOrderCount());
            orderCount += count;
            if (isRevenue(row.getOrderStatus())) {
                revenue = revenue.add(nz(row.getTotalAmount()));
                // COD still to collect on an active (non-cancelled/rejected) order.
                codOutstanding = codOutstanding.add(nz(row.getOutstandingAmount()));
            }
            OrderStatusGroup group = STATUS_TO_GROUP.get(row.getOrderStatus());
            if (group != null) {
                byGroup.merge(group, count, Long::sum);
            }
        }

        /** Folds in the orders of one (source, status) group created this month. */
        void addMonth(SourceStatusRow row) {
            monthOrderCount += nzLong(row.getOrderCount());
            if (isRevenue(row.getOrderStatus())) {
                monthRevenue = monthRevenue.add(nz(row.getTotalAmount()));
            }
        }

        ChannelStats toStats() {
            List<StatusCount> breakdown = new ArrayList<>();
            // Emit groups in lifecycle order, only those with orders.
            for (OrderStatusGroup group : OrderStatusGroup.values()) {
                long count = byGroup.getOrDefault(group, 0L);
                if (count > 0) {
                    breakdown.add(new StatusCount(
                            group.name(), GROUP_LABELS.getOrDefault(group, group.name()), count));
                }
            }
            return new ChannelStats(orderCount, revenue, codOutstanding,
                    monthOrderCount, monthRevenue, breakdown);
        }
    }
}
