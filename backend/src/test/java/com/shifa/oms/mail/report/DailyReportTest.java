package com.shifa.oms.mail.report;

import com.shifa.oms.statemachine.OrderStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the pure consolidated-report builder {@link DailyReport#build}
 * (Consolidated Daily Report feature). Exercised without the scheduler, the DB,
 * or Mockito by feeding plain {@link ReportOrder} records — mirroring the style
 * of {@link com.shifa.oms.mail.DailyDigestJobTest}.
 */
class DailyReportTest {

    private static final LocalDate DAY = LocalDate.of(2024, 5, 10);

    private static ReportOrder order(OrderStatus status, String total, String cod, String received,
                                     String customer, String mobile, Long spId, String spName) {
        return new ReportOrder(
                status,
                total == null ? null : new BigDecimal(total),
                cod == null ? null : new BigDecimal(cod),
                received == null ? null : new BigDecimal(received),
                customer, mobile, spId, spName);
    }

    /** A representative day: three salespersons, mixed statuses, repeat + rejected orders. */
    private static List<ReportOrder> sampleDay() {
        return List.of(
                // Priya (id 1): two qualifying prepaid sales + one delivered.
                order(OrderStatus.APPROVED, "100.00", "0", "100.00", "Asha", "9000000001", 1L, "Priya"),
                order(OrderStatus.DELIVERED, "200.00", "0", "200.00", "Bina", "9000000002", 1L, "Priya"),
                // Ravi (id 2): one big COD sale (top customer) + one cancelled (excluded).
                order(OrderStatus.DISPATCHED, "500.00", "500.00", "0", "Chetan", "9000000003", 2L, "Ravi"),
                order(OrderStatus.CANCELLED, "999.00", "0", "0", "Deep", "9000000004", 2L, "Ravi"),
                // Sana (id 3): one rejected (excluded) — she has no qualifying sales.
                order(OrderStatus.REJECTED, "777.00", "0", "0", "Asha", "9000000001", 3L, "Sana"),
                // Repeat customer Asha (same mobile as first order) with another prepaid sale under Priya.
                order(OrderStatus.APPROVED, "50.00", "0", "50.00", "Asha", "9000000001", 1L, "Priya"));
    }

    @Test
    void overallTotalsExcludeRejectedAndCancelled() {
        DailyReport report = DailyReport.build(DAY, sampleDay());
        DailyReport.Overall o = report.overall();

        // Qualifying orders: 100 + 200 + 500 + 50 = 850 across 4 orders.
        assertThat(o.orderCount()).isEqualTo(4);
        assertThat(o.totalSales()).isEqualByComparingTo("850.00");
        // COD amount only from the dispatched COD order.
        assertThat(o.codAmount()).isEqualByComparingTo("500.00");
        // Prepaid received: 100 + 200 + 50 = 350.
        assertThat(o.prepaidAmountReceived()).isEqualByComparingTo("350.00");
        assertThat(o.deliveredCount()).isEqualTo(1);
        assertThat(o.cancelledRejectedCount()).isEqualTo(2);
    }

    @Test
    void perSalespersonGroupedAndSortedByValueDesc() {
        DailyReport report = DailyReport.build(DAY, sampleDay());
        List<DailyReport.SalespersonRow> rows = report.salespersons();

        // Sana has only excluded orders → no qualifying-sales row.
        assertThat(rows).hasSize(2);
        // Priya: 100 + 200 + 50 = 350 over 3 orders; Ravi: 500 over 1 order.
        // Sorted by sales value desc → Ravi (500) first, Priya (350) second.
        assertThat(rows.get(0).salespersonName()).isEqualTo("Ravi");
        assertThat(rows.get(0).salespersonId()).isEqualTo(2L);
        assertThat(rows.get(0).orderCount()).isEqualTo(1);
        assertThat(rows.get(0).salesValue()).isEqualByComparingTo("500.00");

        assertThat(rows.get(1).salespersonName()).isEqualTo("Priya");
        assertThat(rows.get(1).orderCount()).isEqualTo(3);
        assertThat(rows.get(1).salesValue()).isEqualByComparingTo("350.00");
    }

    @Test
    void statusBreakdownCountsEveryStatusInLifecycleOrder() {
        DailyReport report = DailyReport.build(DAY, sampleDay());
        List<DailyReport.StatusCount> breakdown = report.statusBreakdown();

        // Present statuses: APPROVED x2, DELIVERED, DISPATCHED, CANCELLED, REJECTED.
        assertThat(breakdown).extracting(DailyReport.StatusCount::status)
                .containsExactly(
                        OrderStatus.APPROVED,
                        OrderStatus.REJECTED,
                        OrderStatus.DISPATCHED,
                        OrderStatus.DELIVERED,
                        OrderStatus.CANCELLED);
        assertThat(breakdown.stream()
                .filter(s -> s.status() == OrderStatus.APPROVED)
                .findFirst().orElseThrow().count()).isEqualTo(2);
        int total = breakdown.stream().mapToInt(DailyReport.StatusCount::count).sum();
        assertThat(total).isEqualTo(6);
    }

    @Test
    void distinctCustomersCountedByMobileAndTopCustomerByValue() {
        DailyReport report = DailyReport.build(DAY, sampleDay());

        // Distinct mobiles across ALL orders: ...001, ...002, ...003, ...004 = 4.
        assertThat(report.distinctCustomerCount()).isEqualTo(4);

        // Top qualifying-sales customer: Chetan (9000000003) with 500.
        // (Asha totals 150 across two qualifying orders; her rejected order adds 0.)
        assertThat(report.topCustomer()).isNotNull();
        assertThat(report.topCustomer().customerMobile()).isEqualTo("9000000003");
        assertThat(report.topCustomer().customerName()).isEqualTo("Chetan");
        assertThat(report.topCustomer().value()).isEqualByComparingTo("500.00");
    }

    @Test
    void emptyDayProducesZeroedReport() {
        DailyReport report = DailyReport.build(DAY, List.of());

        assertThat(report.overall().orderCount()).isZero();
        assertThat(report.overall().totalSales()).isEqualByComparingTo("0");
        assertThat(report.salespersons()).isEmpty();
        assertThat(report.statusBreakdown()).isEmpty();
        assertThat(report.distinctCustomerCount()).isZero();
        assertThat(report.topCustomer()).isNull();
    }

    @Test
    void allExcludedDayHasNoSalesButKeepsStatusAndCustomerCounts() {
        List<ReportOrder> orders = List.of(
                order(OrderStatus.REJECTED, "100.00", "0", "0", "A", "9000000001", 1L, "Priya"),
                order(OrderStatus.CANCELLED, "200.00", "0", "0", "B", "9000000002", 2L, "Ravi"));

        DailyReport report = DailyReport.build(DAY, orders);

        assertThat(report.overall().orderCount()).isZero();
        assertThat(report.overall().totalSales()).isEqualByComparingTo("0");
        assertThat(report.overall().cancelledRejectedCount()).isEqualTo(2);
        assertThat(report.salespersons()).isEmpty();
        // Status breakdown + distinct customers still reflect every order.
        assertThat(report.statusBreakdown()).hasSize(2);
        assertThat(report.distinctCustomerCount()).isEqualTo(2);
        assertThat(report.topCustomer()).isNull();
    }
}
