package com.shifa.oms.reporting.domain;

import com.shifa.oms.order.LeadSource;
import com.shifa.oms.order.domain.PaymentStatus;
import com.shifa.oms.reporting.domain.ReportRows.CountRow;
import com.shifa.oms.reporting.domain.ReportRows.DeliveryOutcome;
import com.shifa.oms.statemachine.OrderStatus;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.From;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based test for the new grouped-count reports and the delivery
 * success rate (design §6.8, §Correctness Properties (14), §10.1).
 *
 * Feature: role-based-order-workflow, Property 14: Report groupings and the
 * delivery success rate are exact.
 *
 * Exercises the pure {@link ReportAggregator} in-memory over generated orders:
 * group counts equal the multiset grouping of the in-range orders and sum to the
 * in-range count; the delivery success rate is
 * {@code DELIVERED / (DELIVERED + CUSTOMER_REJECTED + DELIVERY_FAILED +
 * CANCELLED) * 100} (0 when the denominator is 0), with the two failure outcomes
 * counted separately. No Spring/DB, no Mockito mocks of concrete classes.
 *
 * **Validates: Requirements 3.6, 16.1, 16.2, 16.3, 16.4**
 */
class ReportGroupingPropertyTest {

    private final ReportAggregator aggregator = new ReportAggregator();

    private static final LocalDate EPOCH = LocalDate.of(2024, 1, 1);

    // Feature: role-based-order-workflow, Property 14: Report groupings and the delivery success rate are exact
    // **Validates: Requirements 3.6, 16.1, 16.2, 16.3, 16.4**
    @Property(tries = 200)
    void groupingsAreExactAndSumToInRangeCount(
            @ForAll @Size(max = 50) List<@From("orders") OrderReportRecord> orders,
            @ForAll @IntRange(min = 0, max = 120) int fromOffset,
            @ForAll @IntRange(min = 0, max = 120) int span) {

        LocalDate from = EPOCH.plusDays(fromOffset);
        LocalDate to = from.plusDays(span);
        DateRange window = new DateRange(from, to);

        List<OrderReportRecord> inRange = new ArrayList<>();
        for (OrderReportRecord o : orders) {
            if (window.contains(o.orderDate())) {
                inRange.add(o);
            }
        }
        long inRangeCount = inRange.size();

        // --- Lead source grouping (NULL bucketed as UNSPECIFIED, Req 16.1). ---
        Map<String, Long> expectedLead = new LinkedHashMap<>();
        for (OrderReportRecord o : inRange) {
            String key = o.leadSource() == null
                    ? ReportAggregator.UNSPECIFIED_LEAD_SOURCE : o.leadSource().name();
            expectedLead.merge(key, 1L, Long::sum);
        }
        assertGrouping(aggregator.ordersByLeadSource(orders, window), expectedLead, inRangeCount);

        // --- Status grouping (Req 16.2). ---
        Map<String, Long> expectedStatus = new LinkedHashMap<>();
        for (OrderReportRecord o : inRange) {
            expectedStatus.merge(o.orderStatus().name(), 1L, Long::sum);
        }
        assertGrouping(aggregator.ordersByStatus(orders, window), expectedStatus, inRangeCount);

        // --- Salesperson grouping (Req 16.3). ---
        Map<String, Long> expectedSalesperson = new LinkedHashMap<>();
        for (OrderReportRecord o : inRange) {
            String key = o.salespersonId() == null
                    ? ReportAggregator.UNSPECIFIED_SALESPERSON : Long.toString(o.salespersonId());
            expectedSalesperson.merge(key, 1L, Long::sum);
        }
        assertGrouping(aggregator.ordersBySalesperson(orders, window), expectedSalesperson, inRangeCount);
    }

    // Feature: role-based-order-workflow, Property 14: Report groupings and the delivery success rate are exact
    // **Validates: Requirements 3.6, 16.4**
    @Property(tries = 200)
    void deliverySuccessRateIsExact(
            @ForAll @Size(max = 50) List<@From("orders") OrderReportRecord> orders,
            @ForAll @IntRange(min = 0, max = 120) int fromOffset,
            @ForAll @IntRange(min = 0, max = 120) int span) {

        LocalDate from = EPOCH.plusDays(fromOffset);
        LocalDate to = from.plusDays(span);
        DateRange window = new DateRange(from, to);

        long delivered = 0;
        long customerRejected = 0;
        long deliveryFailed = 0;
        long cancelled = 0;
        for (OrderReportRecord o : orders) {
            if (!window.contains(o.orderDate())) {
                continue;
            }
            switch (o.orderStatus()) {
                case DELIVERED -> delivered++;
                case CUSTOMER_REJECTED -> customerRejected++;
                case DELIVERY_FAILED -> deliveryFailed++;
                case CANCELLED -> cancelled++;
                default -> { }
            }
        }
        long denominator = delivered + customerRejected + deliveryFailed + cancelled;
        BigDecimal expectedRate = denominator == 0
                ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.valueOf(delivered)
                        .multiply(BigDecimal.valueOf(100))
                        .divide(BigDecimal.valueOf(denominator), 2, RoundingMode.HALF_UP);

        DeliveryOutcome outcome = aggregator.deliveryOutcome(orders, window);

        // The two failure outcomes are counted separately (Req 16.4).
        assertThat(outcome.delivered()).isEqualTo(delivered);
        assertThat(outcome.customerRejected()).isEqualTo(customerRejected);
        assertThat(outcome.deliveryFailed()).isEqualTo(deliveryFailed);
        assertThat(outcome.cancelled()).isEqualTo(cancelled);
        assertThat(outcome.successRate()).isEqualByComparingTo(expectedRate);
        if (denominator == 0) {
            assertThat(outcome.successRate()).isEqualByComparingTo(BigDecimal.ZERO);
        }
    }

    private void assertGrouping(List<CountRow> rows, Map<String, Long> expected, long inRangeCount) {
        Map<String, Long> actual = new LinkedHashMap<>();
        long sum = 0;
        for (CountRow r : rows) {
            actual.put(r.key(), r.orderCount());
            sum += r.orderCount();
        }
        assertThat(actual).isEqualTo(expected);
        // The group counts sum to the number of in-range orders.
        assertThat(sum).isEqualTo(inRangeCount);
    }

    // --- Generators ---------------------------------------------------------

    @Provide
    Arbitrary<OrderReportRecord> orders() {
        Arbitrary<Long> id = Arbitraries.longs().between(1, 1_000_000);
        Arbitrary<Integer> dayOffset = Arbitraries.integers().between(0, 240);
        // Include null salespersons and null lead sources to exercise UNSPECIFIED bucketing.
        Arbitrary<Long> salesperson = Arbitraries.longs().between(0, 4)
                .map(v -> v == 0 ? null : v);
        Arbitrary<LeadSource> lead = Arbitraries.of(
                LeadSource.WHATSAPP, LeadSource.INSTAGRAM, LeadSource.FACEBOOK,
                LeadSource.GOOGLE, LeadSource.OFFLINE, LeadSource.OTHER, null);
        Arbitrary<OrderStatus> status = Arbitraries.of(OrderStatus.values());
        Arbitrary<PaymentStatus> payment = Arbitraries.of(PaymentStatus.values());

        return Combinators.combine(id, dayOffset, salesperson, lead, status, payment)
                .as((oid, off, sp, ls, st, pay) -> new OrderReportRecord(
                        oid, "SHR-" + oid, EPOCH.plusDays(off), sp,
                        "Cust" + oid, "9000000000", "Maharashtra", List.of(),
                        BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN, pay, st,
                        "N/A", "N/A", null, ls));
    }
}
