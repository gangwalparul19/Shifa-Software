package com.shifa.oms.order;

import com.shifa.oms.order.dto.ChannelSummaryResponse.StatusCount;
import com.shifa.oms.order.dto.DeliveryPartnerSummaryResponse;
import com.shifa.oms.order.dto.DeliveryPartnerSummaryResponse.PartnerStats;
import com.shifa.oms.statemachine.OrderStatus;
import org.springframework.beans.factory.annotation.Autowired;
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
 * Computes the ADMIN-only delivery-partner dashboard: order metrics split by the
 * three fulfilment partners (QuikShipX courier / in-house "Ishika Enterprise" /
 * POS store), plus the combined total, over a chosen date window.
 *
 * <p>Mirrors {@link ChannelSummaryService} (same window loading, revenue and COD
 * rules, status-group breakdown) but partitions by delivery partner rather than
 * origin channel. Each order lands in exactly one partner:
 * <ul>
 *   <li>{@code deliveryMethod == QUIKSHIPX} → QuikShipX;</li>
 *   <li>else {@code source == STORE} → POS;</li>
 *   <li>else → in-house.</li>
 * </ul>
 * Read-only; iterates the windowed orders in memory (the business's order book).
 * Revenue EXCLUDES rejected/cancelled orders; "COD to collect" is the persisted
 * {@code customer_outstanding} on active orders.
 */
@Service
public class DeliveryPartnerService {

    /** Non-revenue statuses excluded from sales totals (mirrors ChannelSummaryService). */
    private static final Set<OrderStatus> NON_REVENUE = EnumSet.of(
            OrderStatus.REJECTED, OrderStatus.PAYMENT_REJECTED, OrderStatus.CANCELLED);

    /** Business zone for the "this month" window (IST), matching order-entry rules. */
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Kolkata");

    /** Human labels for the lifecycle groups shown in the breakdown. */
    private static final Map<OrderStatusGroup, String> GROUP_LABELS = new EnumMap<>(OrderStatusGroup.class);

    static {
        GROUP_LABELS.put(OrderStatusGroup.PENDING_APPROVAL, "Pending Approval");
        GROUP_LABELS.put(OrderStatusGroup.PROCESSING, "Processing");
        GROUP_LABELS.put(OrderStatusGroup.SHIPPED, "In Transit");
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

    /** The three partner buckets an order can fall into. */
    private enum Partner {
        QUIKSHIPX("QuikShipX"),
        IN_HOUSE("In-house (Ishika Enterprise)"),
        POS("POS / Store");

        private final String label;

        Partner(String label) {
            this.label = label;
        }
    }

    private final OrderRepository orderRepository;
    private final Clock clock;

    @Autowired
    public DeliveryPartnerService(OrderRepository orderRepository) {
        this(orderRepository, Clock.system(BUSINESS_ZONE));
    }

    /** Test constructor with a fixed clock (deterministic "this month" window). */
    DeliveryPartnerService(OrderRepository orderRepository, Clock clock) {
        this.orderRepository = orderRepository;
        this.clock = clock;
    }

    /**
     * Builds the delivery-partner summary for the given inclusive date window. A
     * null {@code from}/{@code to} means unbounded on that side (all-time). The
     * this-month figures are always the current calendar month regardless of the
     * window.
     */
    @Transactional(readOnly = true)
    public DeliveryPartnerSummaryResponse summary(LocalDate from, LocalDate to) {
        LocalDateTime start = from != null ? from.atStartOfDay() : null;
        LocalDateTime end = to != null ? LocalDateTime.of(to, LocalTime.MAX) : null;

        List<OrderEntity> orders = loadWindow(start, end);

        LocalDate today = LocalDate.now(clock);
        LocalDateTime monthStart = today.withDayOfMonth(1).atStartOfDay();
        LocalDateTime monthEnd = today.plusMonths(1).withDayOfMonth(1).atStartOfDay();

        Accumulator total = new Accumulator();
        Accumulator quikShipX = new Accumulator();
        Accumulator inHouse = new Accumulator();
        Accumulator pos = new Accumulator();

        for (OrderEntity order : orders) {
            boolean inMonth = withinMonth(order.getCreatedAt(), monthStart, monthEnd);
            total.add(order, inMonth);
            Accumulator bucket = switch (partnerOf(order)) {
                case QUIKSHIPX -> quikShipX;
                case POS -> pos;
                case IN_HOUSE -> inHouse;
            };
            bucket.add(order, inMonth);
        }

        return new DeliveryPartnerSummaryResponse(
                from != null ? from.toString() : null,
                to != null ? to.toString() : null,
                total.toStats("TOTAL", "All partners"),
                quikShipX.toStats("QUIKSHIPX", Partner.QUIKSHIPX.label),
                inHouse.toStats("IN_HOUSE", Partner.IN_HOUSE.label),
                pos.toStats("POS", Partner.POS.label));
    }

    /**
     * The partner an order belongs to: QuikShipX (courier) wins first; otherwise a
     * store (POS) sale; otherwise in-house.
     */
    private static Partner partnerOf(OrderEntity order) {
        if (order.getDeliveryMethod() == DeliveryMethod.QUIKSHIPX) {
            return Partner.QUIKSHIPX;
        }
        if (order.getSource() == OrderSource.STORE) {
            return Partner.POS;
        }
        return Partner.IN_HOUSE;
    }

    /**
     * Loads the orders in the window. Only the fully-unbounded "all time" case
     * loads every order; any bounded side pushes the filter to SQL via a wide
     * sentinel on the open side.
     */
    private List<OrderEntity> loadWindow(LocalDateTime start, LocalDateTime end) {
        if (start == null && end == null) {
            return orderRepository.findAll();
        }
        LocalDateTime lo = start != null ? start : SENTINEL_MIN;
        LocalDateTime hi = end != null ? end : SENTINEL_MAX;
        return orderRepository.findByCreatedAtBetween(lo, hi);
    }

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

    /** Accumulates per-partner metrics as orders are streamed. */
    private static final class Accumulator {
        private long orderCount;
        private BigDecimal revenue = BigDecimal.ZERO;
        private BigDecimal codToCollect = BigDecimal.ZERO;
        private long monthOrderCount;
        private BigDecimal monthRevenue = BigDecimal.ZERO;
        private final Map<OrderStatusGroup, Long> byGroup = new EnumMap<>(OrderStatusGroup.class);

        void add(OrderEntity order, boolean inMonth) {
            orderCount++;
            boolean revenue = isRevenue(order);
            if (revenue) {
                this.revenue = this.revenue.add(nz(order.getTotalAmount()));
                this.codToCollect = this.codToCollect.add(nz(order.getCustomerOutstanding()));
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

        private long count(OrderStatusGroup group) {
            return byGroup.getOrDefault(group, 0L);
        }

        PartnerStats toStats(String key, String label) {
            List<StatusCount> breakdown = new ArrayList<>();
            for (OrderStatusGroup group : OrderStatusGroup.values()) {
                long count = byGroup.getOrDefault(group, 0L);
                if (count > 0) {
                    breakdown.add(new StatusCount(
                            group.name(), GROUP_LABELS.getOrDefault(group, group.name()), count));
                }
            }
            return new PartnerStats(
                    key, label, orderCount, revenue, codToCollect,
                    count(OrderStatusGroup.PENDING_APPROVAL),
                    count(OrderStatusGroup.PROCESSING),
                    count(OrderStatusGroup.SHIPPED),
                    count(OrderStatusGroup.DELIVERED),
                    count(OrderStatusGroup.CANCELLED),
                    count(OrderStatusGroup.FAILED_RETURNED),
                    monthOrderCount, monthRevenue, breakdown);
        }
    }
}
