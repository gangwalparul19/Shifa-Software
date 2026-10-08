package com.shifa.oms.order;

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
 * channel (portal vs Shopify) plus the combined total, over a chosen date window,
 * with a per-channel status breakdown, COD outstanding, and this-month figures.
 *
 * <p>Read-only; iterates the windowed orders in memory (the volumes are the
 * business's order book, not an unbounded scan) and applies the same money rules
 * used elsewhere: revenue EXCLUDES rejected/cancelled orders, and COD outstanding
 * is the persisted {@code customer_outstanding} on active orders. A Shopify order
 * is {@link OrderSource#SHOPIFY}; "portal" is everything else (SALESPERSON /
 * STOREFRONT).
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

    private final OrderRepository orderRepository;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public ChannelSummaryService(OrderRepository orderRepository) {
        this(orderRepository, Clock.system(BUSINESS_ZONE));
    }

    /** Test constructor with a fixed clock (deterministic "this month" window). */
    ChannelSummaryService(OrderRepository orderRepository, Clock clock) {
        this.orderRepository = orderRepository;
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

        List<OrderEntity> orders = loadWindow(start, end);

        // "This month" boundaries (IST), independent of the requested window.
        LocalDate today = LocalDate.now(clock);
        LocalDateTime monthStart = today.withDayOfMonth(1).atStartOfDay();
        LocalDateTime monthEnd = today.plusMonths(1).withDayOfMonth(1).atStartOfDay();

        Accumulator total = new Accumulator();
        Accumulator portal = new Accumulator();
        Accumulator shopify = new Accumulator();
        Accumulator store = new Accumulator();

        for (OrderEntity order : orders) {
            boolean inMonth = withinMonth(order.getCreatedAt(), monthStart, monthEnd);
            total.add(order, inMonth);
            // Portal = our own online orders (neither Shopify nor an in-shop store
            // sale), so each order lands in exactly one of the three channels.
            Accumulator bucket = switch (order.getSource()) {
                case SHOPIFY -> shopify;
                case STORE -> store;
                default -> portal;
            };
            bucket.add(order, inMonth);
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
     * Loads the orders in the window. Only the fully-unbounded "all time" case
     * loads every order (that genuinely IS the whole order book); any bounded
     * side pushes the filter to SQL via a wide sentinel on the open side, so a
     * one-sided window no longer does {@code findAll()} + a Java filter.
     */
    private List<OrderEntity> loadWindow(LocalDateTime start, LocalDateTime end) {
        if (start == null && end == null) {
            return orderRepository.findAll();
        }
        LocalDateTime lo = start != null ? start : SENTINEL_MIN;
        LocalDateTime hi = end != null ? end : SENTINEL_MAX;
        return orderRepository.findByCreatedAtBetween(lo, hi);
    }

    /** Wide sentinels so a one-sided window still runs as a bounded SQL query. */
    private static final LocalDateTime SENTINEL_MIN = LocalDateTime.of(1970, 1, 1, 0, 0);
    private static final LocalDateTime SENTINEL_MAX = LocalDateTime.of(9999, 12, 31, 23, 59, 59);

    private static boolean withinMonth(LocalDateTime created, LocalDateTime monthStart, LocalDateTime monthEnd) {
        return created != null && !created.isBefore(monthStart) && created.isBefore(monthEnd);
    }

    private static boolean isRevenue(OrderEntity order) {
        return !NON_REVENUE.contains(order.getOrderStatus());
    }

    private static BigDecimal nz(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }

    /** Accumulates per-channel metrics as orders are streamed. */
    private static final class Accumulator {
        private long orderCount;
        private BigDecimal revenue = BigDecimal.ZERO;
        private BigDecimal codOutstanding = BigDecimal.ZERO;
        private long monthOrderCount;
        private BigDecimal monthRevenue = BigDecimal.ZERO;
        private final Map<OrderStatusGroup, Long> byGroup = new EnumMap<>(OrderStatusGroup.class);

        void add(OrderEntity order, boolean inMonth) {
            orderCount++;
            boolean revenue = isRevenue(order);
            if (revenue) {
                this.revenue = this.revenue.add(nz(order.getTotalAmount()));
                // COD still to collect on an active (non-cancelled/rejected) order.
                this.codOutstanding = this.codOutstanding.add(nz(order.getCustomerOutstanding()));
            }
            if (inMonth) {
                monthOrderCount++;
                if (revenue) {
                    monthRevenue = monthRevenue.add(nz(order.getTotalAmount()));
                }
            }
            OrderStatusGroup group = STATUS_TO_GROUP.get(order.getOrderStatus());
            if (group != null) {
                byGroup.merge(group, 1L, Long::sum);
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
