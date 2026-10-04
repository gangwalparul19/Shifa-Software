package com.shifa.oms.order;

import com.shifa.oms.order.dto.ChannelMarginResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Multi-channel revenue attribution with estimated gross margin (ENHANCEMENT 3.6):
 * layers product cost (V79) onto the per-channel revenue so the owner sees true
 * margin per channel, not just revenue. Read-only SQL aggregation (two GROUP BY
 * queries — revenue without the line fan-out, COGS with the line join).
 *
 * <p>Channels mirror {@link ChannelSummaryService}: SHOPIFY, STORE, else Portal.
 */
@Service
public class ChannelMarginService {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Kolkata");
    private static final LocalDateTime SENTINEL_MIN = LocalDateTime.of(1970, 1, 1, 0, 0);
    private static final LocalDateTime SENTINEL_MAX = LocalDateTime.of(9999, 12, 31, 23, 59, 59);

    private final OrderRepository orderRepository;
    @SuppressWarnings("unused")
    private final Clock clock;

    @Autowired
    public ChannelMarginService(OrderRepository orderRepository) {
        this(orderRepository, Clock.system(BUSINESS_ZONE));
    }

    /** Package-visible constructor allowing a fixed clock in tests. */
    ChannelMarginService(OrderRepository orderRepository, Clock clock) {
        this.orderRepository = orderRepository;
        this.clock = clock;
    }

    /**
     * Per-channel revenue + estimated margin for the inclusive date window. A null
     * {@code from}/{@code to} is unbounded on that side.
     */
    @Transactional(readOnly = true)
    public ChannelMarginResponse margins(LocalDate from, LocalDate to) {
        LocalDateTime start = from != null ? from.atStartOfDay() : SENTINEL_MIN;
        LocalDateTime end = to != null ? LocalDateTime.of(to, LocalTime.MAX) : SENTINEL_MAX;

        // Revenue/discount per channel (no line fan-out), COGS per channel (line join).
        Map<String, Acc> byChannel = new LinkedHashMap<>();
        for (OrderRepository.ChannelRevenueRow r : orderRepository.channelRevenueBetween(start, end)) {
            Acc a = byChannel.computeIfAbsent(label(r.getChannel()), k -> new Acc());
            a.orderCount += r.getOrderCount();
            a.revenue = a.revenue.add(nz(r.getRevenue()));
            a.discount = a.discount.add(nz(r.getDiscount()));
        }
        for (OrderRepository.ChannelCogsRow c : orderRepository.channelCogsBetween(start, end)) {
            Acc a = byChannel.computeIfAbsent(label(c.getChannel()), k -> new Acc());
            a.cogs = a.cogs.add(nz(c.getCogs()));
            a.lineValueWithCost = a.lineValueWithCost.add(nz(c.getLineValueWithCost()));
            a.lineValueWithoutCost = a.lineValueWithoutCost.add(nz(c.getLineValueWithoutCost()));
        }

        List<ChannelMarginResponse.ChannelMargin> channels = new ArrayList<>();
        Acc total = new Acc();
        for (Map.Entry<String, Acc> e : byChannel.entrySet()) {
            channels.add(e.getValue().toRow(e.getKey()));
            total.add(e.getValue());
        }
        // Highest revenue first.
        channels.sort(Comparator.comparing(ChannelMarginResponse.ChannelMargin::revenue).reversed());

        return new ChannelMarginResponse(
                from != null ? from.toString() : null,
                to != null ? to.toString() : null,
                channels,
                total.toRow("Total"));
    }

    /** Maps the stored {@code orders.source} name to a channel label. */
    private static String label(String source) {
        if (source == null) {
            return "Portal";
        }
        return switch (source) {
            case "SHOPIFY" -> "Shopify";
            case "STORE" -> "Store (POS)";
            default -> "Portal";
        };
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static BigDecimal scale(BigDecimal v) {
        return nz(v).setScale(2, RoundingMode.HALF_UP);
    }

    private static double pct(BigDecimal numerator, BigDecimal denominator) {
        if (denominator == null || denominator.signum() == 0) {
            return 0.0;
        }
        return numerator.multiply(BigDecimal.valueOf(100))
                .divide(denominator, 1, RoundingMode.HALF_UP).doubleValue();
    }

    /** Mutable per-channel accumulator. */
    private static final class Acc {
        private long orderCount;
        private BigDecimal revenue = BigDecimal.ZERO;
        private BigDecimal discount = BigDecimal.ZERO;
        private BigDecimal cogs = BigDecimal.ZERO;
        private BigDecimal lineValueWithCost = BigDecimal.ZERO;
        private BigDecimal lineValueWithoutCost = BigDecimal.ZERO;

        void add(Acc other) {
            orderCount += other.orderCount;
            revenue = revenue.add(other.revenue);
            discount = discount.add(other.discount);
            cogs = cogs.add(other.cogs);
            lineValueWithCost = lineValueWithCost.add(other.lineValueWithCost);
            lineValueWithoutCost = lineValueWithoutCost.add(other.lineValueWithoutCost);
        }

        ChannelMarginResponse.ChannelMargin toRow(String channel) {
            BigDecimal margin = revenue.subtract(cogs);
            BigDecimal lineValue = lineValueWithCost.add(lineValueWithoutCost);
            return new ChannelMarginResponse.ChannelMargin(
                    channel, orderCount, scale(revenue), scale(discount), scale(cogs),
                    scale(margin), pct(margin, revenue), pct(lineValueWithCost, lineValue));
        }
    }
}
