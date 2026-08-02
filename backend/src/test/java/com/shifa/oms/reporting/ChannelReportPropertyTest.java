package com.shifa.oms.reporting;

import com.shifa.oms.order.OrderSource;
import com.shifa.oms.reporting.domain.DateRange;
import com.shifa.oms.reporting.domain.OrderReportRecord;
import com.shifa.oms.reporting.domain.ReportAggregator;
import com.shifa.oms.reporting.domain.ReportRows;
import com.shifa.oms.reporting.domain.ReportTableBuilder;
import com.shifa.oms.reporting.domain.ReportType;
import com.shifa.oms.reporting.domain.TabularData;
import com.shifa.oms.statemachine.OrderStatus;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Feature: shopify-quikshipx-order-sync, Property 26: channel aggregates are correct and
 * revenue excludes cancelled orders, and Property 27: the channel report partitions the
 * window.
 *
 * <p>Property 27 is the one that protects the numbers an admin will act on. If the buckets
 * did not partition the window, the two channel figures would silently fail to add up to
 * the business total, and the natural conclusion — "one channel is underperforming" — would
 * be an artefact of lost rows rather than a fact.
 *
 * <p>Validates: Requirements 12.1, 12.4, 12.6, 12.7
 */
class ChannelReportPropertyTest {

    private final ReportAggregator aggregator = new ReportAggregator();
    private final ReportTableBuilder tableBuilder = new ReportTableBuilder();

    // --- Property 27: the buckets partition the window -----------------------

    @Property(tries = 500)
    void theChannelCountsSumToTheWindowedRecordCount(
            @ForAll @Size(max = 30) List<@net.jqwik.api.From("records") OrderReportRecord> records,
            @ForAll("windows") DateRange window) {

        List<ReportRows.ChannelRow> rows = aggregator.ordersByChannel(records, window);

        long windowed = records.stream().filter(inWindow(window)).count();
        long summed = rows.stream().mapToLong(ReportRows.ChannelRow::orderCount).sum();

        assertThat(summed).isEqualTo(windowed);
        // Exactly the two channels, always, so a channel with no orders reads as zero
        // rather than disappearing (Req 12.5).
        assertThat(rows.stream().map(ReportRows.ChannelRow::channel).toList())
                .containsExactlyInAnyOrder(
                        OrderSource.SHOPIFY_API.name(), OrderSource.SHIFA_ADMIN.name());
    }

    @Property(tries = 300)
    void everyLegacySourceValueCountsAsShifaAdmin(
            @ForAll OrderSource source,
            @ForAll @IntRange(min = 1, max = 5) int count) {

        List<OrderReportRecord> records = java.util.stream.IntStream.range(0, count)
                .mapToObj(i -> record((long) i, LocalDate.of(2026, 6, 15),
                        OrderStatus.DELIVERED, new BigDecimal("100.00"), source))
                .collect(Collectors.toList());

        Map<String, Long> byChannel = aggregator.ordersByChannel(records, DateRange.all()).stream()
                .collect(Collectors.toMap(ReportRows.ChannelRow::channel,
                        ReportRows.ChannelRow::orderCount));

        // SALESPERSON and STOREFRONT are pre-Shopify names for Shifa's own orders. Reading
        // them as a third, unnamed channel would drop them out of both tiles.
        String expected = source.canonical().name();
        assertThat(byChannel.get(expected)).isEqualTo(count);
        assertThat(byChannel.values().stream().mapToLong(Long::longValue).sum()).isEqualTo(count);
    }

    // --- Property 26: the aggregates are the independently computed ones ------

    @Property(tries = 500)
    void eachRowMatchesTheIndependentlyComputedAggregate(
            @ForAll @Size(max = 30) List<@net.jqwik.api.From("records") OrderReportRecord> records,
            @ForAll("windows") DateRange window) {

        for (ReportRows.ChannelRow row : aggregator.ordersByChannel(records, window)) {
            List<OrderReportRecord> mine = records.stream()
                    .filter(inWindow(window))
                    .filter(r -> r.canonicalChannel().name().equals(row.channel()))
                    .toList();

            assertThat(row.orderCount()).isEqualTo(mine.size());
            assertThat(row.deliveredCount()).isEqualTo(mine.stream()
                    .filter(r -> r.orderStatus() == OrderStatus.DELIVERED
                            || r.orderStatus() == OrderStatus.COD_COLLECTED
                            || r.orderStatus() == OrderStatus.CLOSED)
                    .count());
            // Revenue excludes written-off orders; the order count deliberately does not,
            // so "orders taken" and "revenue earned" remain distinguishable.
            assertThat(row.revenue()).isEqualByComparingTo(mine.stream()
                    .filter(r -> r.orderStatus() != OrderStatus.REJECTED
                            && r.orderStatus() != OrderStatus.CANCELLED)
                    .map(OrderReportRecord::totalAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add));
        }
    }

    @Test
    void aCancelledOrderIsCountedButEarnsNoRevenue() {
        List<OrderReportRecord> records = List.of(
                record(1L, LocalDate.of(2026, 6, 1), OrderStatus.DELIVERED,
                        new BigDecimal("500.00"), OrderSource.SHOPIFY_API),
                record(2L, LocalDate.of(2026, 6, 2), OrderStatus.CANCELLED,
                        new BigDecimal("900.00"), OrderSource.SHOPIFY_API),
                record(3L, LocalDate.of(2026, 6, 3), OrderStatus.REJECTED,
                        new BigDecimal("700.00"), OrderSource.SHOPIFY_API));

        ReportRows.ChannelRow shopify = aggregator.ordersByChannel(records, DateRange.all()).stream()
                .filter(r -> r.channel().equals(OrderSource.SHOPIFY_API.name()))
                .findFirst().orElseThrow();

        assertThat(shopify.orderCount()).isEqualTo(3);
        assertThat(shopify.revenue()).isEqualByComparingTo("500.00");
        assertThat(shopify.deliveredCount()).isEqualTo(1);
    }

    // --- The rendered table (what the export reproduces) ---------------------

    @Property(tries = 200)
    void theRenderedTableCarriesEveryAggregateInAStableColumnOrder(
            @ForAll @Size(max = 20) List<@net.jqwik.api.From("records") OrderReportRecord> records) {

        TabularData table = tableBuilder.build(
                ReportType.ORDERS_BY_CHANNEL, records, DateRange.all());

        assertThat(table.headers()).containsExactly("Channel", "Orders", "Revenue", "Delivered");
        assertThat(table.rows()).hasSize(2);
        List<ReportRows.ChannelRow> rows = aggregator.ordersByChannel(records, DateRange.all());
        for (int i = 0; i < rows.size(); i++) {
            assertThat(table.rows().get(i)).containsExactly(
                    rows.get(i).channel(),
                    Long.toString(rows.get(i).orderCount()),
                    rows.get(i).revenue().toPlainString(),
                    Long.toString(rows.get(i).deliveredCount()));
        }
    }

    @Test
    void theReportTypeIsParseableFromEveryAliasTheApiAccepts() {
        for (String alias : new String[]{
                "orders-by-channel", "ORDERS_BY_CHANNEL", "by-channel", "channel", " Channel "}) {
            assertThat(ReportType.from(alias)).isEqualTo(ReportType.ORDERS_BY_CHANNEL);
        }
        // Not a module report, so it flows through the normal order-record path and the
        // existing Excel/PDF exporters reproduce it for free.
        assertThat(ReportType.ORDERS_BY_CHANNEL.isModuleReport()).isFalse();
        assertThat(ReportType.ORDERS_BY_CHANNEL.isMoneyReport()).isFalse();
    }

    @Test
    void anAbsentChannelOnALegacyRecordReadsAsShifaAdmin() {
        // The 17-argument constructor is what every pre-existing caller uses; its records
        // must not fall into a third bucket.
        OrderReportRecord legacy = new OrderReportRecord(
                1L, "SHR-1", LocalDate.of(2026, 6, 1), 5L, "Asha", "9812345678", "Maharashtra",
                List.of(), new BigDecimal("100.00"), BigDecimal.ZERO, new BigDecimal("100.00"),
                null, OrderStatus.DELIVERED, "N/A", "N/A", null, null);

        assertThat(legacy.canonicalChannel()).isEqualTo(OrderSource.SHIFA_ADMIN);
    }

    // ------------------------------------------------------------------
    // Generators
    // ------------------------------------------------------------------

    private static Predicate<OrderReportRecord> inWindow(DateRange window) {
        return r -> r.orderDate() != null && window.contains(r.orderDate());
    }

    private static OrderReportRecord record(Long id, LocalDate date, OrderStatus status,
                                            BigDecimal total, OrderSource channel) {
        return new OrderReportRecord(
                id, "SHR-" + id, date, null, "Customer " + id, "9812345678", "Maharashtra",
                List.of(), total, BigDecimal.ZERO, total, null, status,
                "N/A", "N/A", null, null, channel);
    }

    @Provide
    Arbitrary<OrderReportRecord> records() {
        Arbitrary<Integer> days = Arbitraries.integers().between(1, 28);
        Arbitrary<OrderStatus> statuses = Arbitraries.of(OrderStatus.values());
        Arbitrary<OrderSource> channels = Arbitraries.of(OrderSource.values());
        Arbitrary<Integer> amounts = Arbitraries.integers().between(0, 5_000);
        return Combinators.combine(days, statuses, channels, amounts).as(
                (day, status, channel, amount) -> record(
                        (long) (day * 100 + amount % 97),
                        LocalDate.of(2026, 6, day),
                        status,
                        new BigDecimal(amount).setScale(2),
                        channel));
    }

    @Provide
    Arbitrary<DateRange> windows() {
        return Arbitraries.of(
                DateRange.all(),
                new DateRange(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30)),
                new DateRange(LocalDate.of(2026, 6, 10), LocalDate.of(2026, 6, 20)),
                new DateRange(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31)));
    }
}
