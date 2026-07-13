package com.shifa.oms.analytics;

import com.shifa.oms.analytics.dto.RetentionReport;
import com.shifa.oms.analytics.dto.RetentionReport.CohortRow;
import com.shifa.oms.analytics.dto.RetentionReport.ReorderBucket;
import com.shifa.oms.order.OrderRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Cohort / retention analytics (FEATURE-ROADMAP §6.3): repeat-purchase rate,
 * months-to-reorder distribution, and a monthly cohort-retention grid — all
 * computed in memory from a lightweight (mobile, created_at) projection over the
 * orders table, keyed by customer mobile.
 */
@Service
public class RetentionService {

    private static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("yyyy-MM");
    private static final int DEFAULT_COHORTS = 6;
    private static final int MIN_COHORTS = 3;
    private static final int MAX_COHORTS = 12;

    private final OrderRepository orderRepository;
    private final Clock clock;

    @Autowired
    public RetentionService(OrderRepository orderRepository) {
        this(orderRepository, Clock.systemDefaultZone());
    }

    RetentionService(OrderRepository orderRepository, Clock clock) {
        this.orderRepository = orderRepository;
        this.clock = clock;
    }

    /**
     * The retention report over the last {@code months} acquisition cohorts.
     *
     * @param months number of monthly cohorts (clamped 3..12, default 6)
     */
    @Transactional(readOnly = true)
    public RetentionReport report(Integer months) {
        int cohortCount = clamp(months);

        // Group order timestamps by customer mobile (rows arrive ordered by mobile, time).
        Map<String, List<LocalDateTime>> byCustomer = new LinkedHashMap<>();
        for (OrderRepository.CustomerOrderDateRow r : orderRepository.customerOrderDates()) {
            if (r.getMobile() == null || r.getCreatedAt() == null) {
                continue;
            }
            byCustomer.computeIfAbsent(r.getMobile(), k -> new ArrayList<>()).add(r.getCreatedAt());
        }

        long totalCustomers = byCustomer.size();
        long repeatCustomers = 0;
        long totalOrders = 0;
        long reorderSamples = 0;
        long reorderDaysSum = 0;
        long[] buckets = new long[5]; // 0-7, 8-30, 31-60, 61-90, 90+

        for (List<LocalDateTime> times : byCustomer.values()) {
            totalOrders += times.size();
            if (times.size() >= 2) {
                repeatCustomers++;
                long days = Math.max(0, Duration.between(times.get(0), times.get(1)).toDays());
                reorderDaysSum += days;
                reorderSamples++;
                buckets[bucketOf(days)]++;
            }
        }

        double repeatRate = totalCustomers == 0 ? 0.0
                : round1(repeatCustomers * 100.0 / totalCustomers);
        double avgOrders = totalCustomers == 0 ? 0.0
                : round1((double) totalOrders / totalCustomers);
        double avgDaysToReorder = reorderSamples == 0 ? 0.0
                : round1((double) reorderDaysSum / reorderSamples);

        List<ReorderBucket> reorderBuckets = List.of(
                new ReorderBucket("0–7 days", buckets[0]),
                new ReorderBucket("8–30 days", buckets[1]),
                new ReorderBucket("31–60 days", buckets[2]),
                new ReorderBucket("61–90 days", buckets[3]),
                new ReorderBucket("90+ days", buckets[4]));

        List<CohortRow> cohorts = cohorts(byCustomer, cohortCount);
        return new RetentionReport(totalCustomers, repeatCustomers, repeatRate, avgOrders,
                avgDaysToReorder, reorderBuckets, cohorts);
    }

    /**
     * Builds the cohort-retention grid for the last {@code cohortCount} months.
     * A customer's cohort is their first-order month; retention[k] is the % of
     * that cohort with any order in the k-th month after acquisition.
     */
    private List<CohortRow> cohorts(Map<String, List<LocalDateTime>> byCustomer, int cohortCount) {
        YearMonth current = YearMonth.now(clock);
        YearMonth earliest = current.minusMonths(cohortCount - 1L);

        // cohortMonth -> list of customers' active-month sets (only cohorts in range).
        Map<YearMonth, List<TreeSet<YearMonth>>> cohortMembers = new LinkedHashMap<>();
        for (List<LocalDateTime> times : byCustomer.values()) {
            TreeSet<YearMonth> activeMonths = new TreeSet<>();
            for (LocalDateTime t : times) {
                activeMonths.add(YearMonth.from(t));
            }
            YearMonth first = activeMonths.first();
            if (first.isBefore(earliest) || first.isAfter(current)) {
                continue;
            }
            cohortMembers.computeIfAbsent(first, k -> new ArrayList<>()).add(activeMonths);
        }

        List<CohortRow> rows = new ArrayList<>();
        // Newest cohort first.
        for (int i = 0; i < cohortCount; i++) {
            YearMonth cohort = current.minusMonths(i);
            List<TreeSet<YearMonth>> members = cohortMembers.getOrDefault(cohort, List.of());
            long size = members.size();
            int offsets = (int) (cohortCount - i); // months from cohort to current, inclusive
            List<Double> retention = new ArrayList<>(offsets);
            for (int k = 0; k < offsets; k++) {
                if (size == 0) {
                    retention.add(0.0);
                    continue;
                }
                YearMonth target = cohort.plusMonths(k);
                long active = 0;
                for (TreeSet<YearMonth> months : members) {
                    if (months.contains(target)) {
                        active++;
                    }
                }
                retention.add(round1(active * 100.0 / size));
            }
            rows.add(new CohortRow(cohort.format(MONTH_FMT), size, retention));
        }
        return rows;
    }

    private static int bucketOf(long days) {
        if (days <= 7) {
            return 0;
        }
        if (days <= 30) {
            return 1;
        }
        if (days <= 60) {
            return 2;
        }
        if (days <= 90) {
            return 3;
        }
        return 4;
    }

    private static int clamp(Integer months) {
        if (months == null) {
            return DEFAULT_COHORTS;
        }
        return Math.max(MIN_COHORTS, Math.min(MAX_COHORTS, months));
    }

    private static double round1(double v) {
        return BigDecimal.valueOf(v).setScale(1, RoundingMode.HALF_UP).doubleValue();
    }
}
