package com.shifa.oms.reporting.domain;

import com.shifa.oms.order.domain.PaymentStatus;
import com.shifa.oms.reporting.domain.ReportRows.DailyRow;
import com.shifa.oms.reporting.domain.ReportRows.ProductRow;
import com.shifa.oms.reporting.domain.ReportRows.StateRow;
import com.shifa.oms.statemachine.OrderStatus;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
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
 * Property-based test for report/metrics window aggregation.
 *
 * Feature: shifa-herbal-remedies, Property 23: Reports and metrics aggregate
 * exactly the orders within the selected window. For ANY set of orders and ANY
 * window, each report's rows/aggregations are computed over exactly the orders
 * whose order date is within the window; previous-period % change =
 * (current-previous)/previous*100 (0 when both 0; not-applicable when previous 0
 * and current non-zero); top performers are argmax within the window.
 *
 * Validates: Requirements 19.3, 19.4, 19.7, 20.2
 */
class ReportWindowAggregationPropertyTest {

    private final ReportAggregator aggregator = new ReportAggregator();

    private static final LocalDate EPOCH = LocalDate.of(2024, 1, 1);

    // Feature: shifa-herbal-remedies, Property 23: Reports and metrics aggregate exactly the orders within the selected window
    @Property(tries = 200)
    void aggregationsCoverExactlyTheWindowedOrders(
            @ForAll @Size(max = 40) List<@net.jqwik.api.From("orders") OrderReportRecord> orders,
            @ForAll @IntRange(min = 0, max = 120) int fromOffset,
            @ForAll @IntRange(min = 0, max = 120) int span) {

        LocalDate from = EPOCH.plusDays(fromOffset);
        LocalDate to = from.plusDays(span);
        DateRange window = new DateRange(from, to);

        // The reference set: exactly the orders whose date is within the window (Req 20.2).
        List<OrderReportRecord> expected = new ArrayList<>();
        for (OrderReportRecord o : orders) {
            if (!o.orderDate().isBefore(from) && !o.orderDate().isAfter(to)) {
                expected.add(o);
            }
        }

        // within() returns exactly that set.
        assertThat(aggregator.within(orders, window)).containsExactlyInAnyOrderElementsOf(expected);

        // Order count and total sales are computed over exactly the windowed orders.
        assertThat(aggregator.orderCount(orders, window)).isEqualTo(expected.size());
        BigDecimal expectedSales = BigDecimal.ZERO;
        for (OrderReportRecord o : expected) {
            expectedSales = expectedSales.add(o.totalAmount());
        }
        assertThat(aggregator.totalSales(orders, window))
                .isEqualByComparingTo(expectedSales.setScale(2, RoundingMode.HALF_UP));

        // Daily report: every row date is inside the window, and the counts/sales
        // sum to the windowed totals (Req 20.1, 20.2).
        long dailyOrderSum = 0;
        BigDecimal dailySalesSum = BigDecimal.ZERO;
        for (DailyRow r : aggregator.daily(orders, window)) {
            assertThat(window.contains(r.date())).isTrue();
            dailyOrderSum += r.orderCount();
            dailySalesSum = dailySalesSum.add(r.totalSales());
        }
        assertThat(dailyOrderSum).isEqualTo(expected.size());
        assertThat(dailySalesSum).isEqualByComparingTo(expectedSales.setScale(2, RoundingMode.HALF_UP));

        // Salesperson-wise report: exactly the windowed orders, one row each (Req 20.3).
        assertThat(aggregator.salespersonWise(orders, window))
                .containsExactlyInAnyOrderElementsOf(expected);

        // Product-wise: total quantity equals the windowed product quantities.
        Map<String, Long> expectedQty = new LinkedHashMap<>();
        for (OrderReportRecord o : expected) {
            for (OrderReportRecord.ProductLine line : o.products()) {
                expectedQty.merge(line.productName(), (long) line.quantity(), Long::sum);
            }
        }
        Map<String, Long> actualQty = new LinkedHashMap<>();
        for (ProductRow r : aggregator.productWise(orders, window)) {
            actualQty.put(r.productName(), r.quantity());
        }
        assertThat(actualQty).isEqualTo(expectedQty);

        // State-wise: order counts per state match the windowed set.
        Map<String, Long> expectedStateCounts = new LinkedHashMap<>();
        for (OrderReportRecord o : expected) {
            expectedStateCounts.merge(o.state() == null ? "" : o.state(), 1L, Long::sum);
        }
        Map<String, Long> actualStateCounts = new LinkedHashMap<>();
        for (StateRow r : aggregator.stateWise(orders, window)) {
            actualStateCounts.put(r.state(), r.orderCount());
        }
        assertThat(actualStateCounts).isEqualTo(expectedStateCounts);

        // Top performers are the argmax within the window.
        assertTopPerformers(orders, window, expected);
    }

    private void assertTopPerformers(List<OrderReportRecord> orders, DateRange window,
                                     List<OrderReportRecord> expected) {
        // Top salesperson by total sales.
        Map<Long, BigDecimal> byPerson = new LinkedHashMap<>();
        Map<String, BigDecimal> byState = new LinkedHashMap<>();
        Map<String, BigDecimal> byProductQty = new LinkedHashMap<>();
        for (OrderReportRecord o : expected) {
            if (o.salespersonId() != null) {
                byPerson.merge(o.salespersonId(), o.totalAmount(), BigDecimal::add);
            }
            byState.merge(o.state() == null ? "" : o.state(), o.totalAmount(), BigDecimal::add);
            for (OrderReportRecord.ProductLine line : o.products()) {
                byProductQty.merge(line.productName(), BigDecimal.valueOf(line.quantity()), BigDecimal::add);
            }
        }
        aggregator.topSalesperson(orders, window).ifPresent(top -> {
            BigDecimal max = byPerson.values().stream().max(BigDecimal::compareTo).orElseThrow();
            assertThat(byPerson.get(top)).isEqualByComparingTo(max);
        });
        aggregator.topState(orders, window).ifPresent(top -> {
            BigDecimal max = byState.values().stream().max(BigDecimal::compareTo).orElseThrow();
            assertThat(byState.get(top)).isEqualByComparingTo(max);
        });
        aggregator.topProduct(orders, window).ifPresent(top -> {
            BigDecimal max = byProductQty.values().stream().max(BigDecimal::compareTo).orElseThrow();
            assertThat(byProductQty.get(top)).isEqualByComparingTo(max);
        });
        // When there are no windowed orders, there are no top performers.
        if (expected.isEmpty()) {
            assertThat(aggregator.topSalesperson(orders, window)).isEmpty();
            assertThat(aggregator.topProduct(orders, window)).isEmpty();
            assertThat(aggregator.topState(orders, window)).isEmpty();
        }
    }

    // Feature: shifa-herbal-remedies, Property 23: previous-period percentage change rule
    @Property(tries = 200)
    void previousPeriodPercentChangeFollowsTheRule(
            @ForAll @IntRange(min = 0, max = 100000) int currentUnits,
            @ForAll @IntRange(min = 0, max = 100000) int previousUnits) {
        BigDecimal current = BigDecimal.valueOf(currentUnits);
        BigDecimal previous = BigDecimal.valueOf(previousUnits);
        PercentChange change = PercentChange.of(current, previous);

        boolean curZero = currentUnits == 0;
        boolean prevZero = previousUnits == 0;
        if (prevZero && curZero) {
            assertThat(change.applicable()).isTrue();
            assertThat(change.value()).isEqualByComparingTo(BigDecimal.ZERO);
        } else if (prevZero) {
            assertThat(change.applicable()).isFalse();
        } else {
            BigDecimal expected = current.subtract(previous)
                    .divide(previous, 6, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100))
                    .setScale(2, RoundingMode.HALF_UP);
            assertThat(change.applicable()).isTrue();
            assertThat(change.value()).isEqualByComparingTo(expected);
        }
    }

    // --- Generators ---------------------------------------------------------

    @Provide
    Arbitrary<OrderReportRecord> orders() {
        Arbitrary<Integer> dayOffset = Arbitraries.integers().between(0, 240);
        Arbitrary<Long> salesperson = Arbitraries.longs().between(1, 4);
        Arbitrary<String> state = Arbitraries.of("Maharashtra", "Gujarat", "Delhi", "Karnataka");
        Arbitrary<PaymentStatus> payment = Arbitraries.of(PaymentStatus.values());
        Arbitrary<OrderStatus> status = Arbitraries.of(OrderStatus.values());
        Arbitrary<List<OrderReportRecord.ProductLine>> lines =
                productLine().list().ofMinSize(0).ofMaxSize(4);
        Arbitrary<Long> id = Arbitraries.longs().between(1, 100000);

        return Combinators.combine(id, dayOffset, salesperson, state, payment, status, lines)
                .as((oid, off, sp, st, pay, os, ls) -> {
                    BigDecimal total = BigDecimal.ZERO;
                    for (OrderReportRecord.ProductLine l : ls) {
                        total = total.add(l.lineTotal());
                    }
                    return new OrderReportRecord(
                            oid, "SHR-" + oid, EPOCH.plusDays(off), sp,
                            "Cust" + oid, "9000000000", st, ls,
                            total, BigDecimal.ZERO, total, pay, os, "N/A", "N/A", null);
                });
    }

    private Arbitrary<OrderReportRecord.ProductLine> productLine() {
        Arbitrary<String> name = Arbitraries.of("Amla", "Neem", "Tulsi", "Ashwagandha", "Giloy");
        Arbitrary<Integer> qty = Arbitraries.integers().between(1, 20);
        Arbitrary<Long> rate = Arbitraries.longs().between(1, 500);
        return Combinators.combine(name, qty, rate).as((n, q, r) -> {
            BigDecimal rateDec = BigDecimal.valueOf(r).setScale(2);
            BigDecimal lineTotal = rateDec.multiply(BigDecimal.valueOf(q));
            return new OrderReportRecord.ProductLine(n, q, rateDec, lineTotal);
        });
    }
}
