package com.shifa.oms.reporting;

import com.shifa.oms.order.domain.PaymentStatus;
import com.shifa.oms.reporting.domain.DateRange;
import com.shifa.oms.reporting.domain.OrderReportRecord;
import com.shifa.oms.reporting.domain.ReportTableBuilder;
import com.shifa.oms.reporting.domain.ReportType;
import com.shifa.oms.reporting.domain.TabularData;
import com.shifa.oms.statemachine.OrderStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Focused unit tests for the accountant money/receivables reports added to
 * {@link ReportTableBuilder}: PAYMENTS (daily money), OUTSTANDING (dues chase
 * list, oldest first), and COD_REMITTANCE (COD pending from the courier). Pure —
 * no Spring/DB — exercised over hand-built {@link OrderReportRecord}s.
 */
class FinanceReportTableBuilderTest {

    private final ReportTableBuilder builder = new ReportTableBuilder();
    private final DateRange all = new DateRange(null, null);

    private static OrderReportRecord order(String code, LocalDate date, String total, String received,
                                           String cod, OrderStatus status, String codSettlement) {
        return new OrderReportRecord(
                1L, code, date, 7L, "Cust " + code, "9800000000", "MH", List.of(),
                new BigDecimal(total), new BigDecimal(received), new BigDecimal(cod),
                PaymentStatus.PARTIALLY_PAID, status, codSettlement, "N/A", "AWB" + code);
    }

    // Fully paid, delivered — no dues, nothing pending from courier.
    private final OrderReportRecord paid =
            order("A", LocalDate.of(2025, 3, 1), "1000", "1000", "0", OrderStatus.DELIVERED, "N/A");
    // COD out for delivery, courier hasn't remitted — outstanding + COD pending.
    private final OrderReportRecord codPending =
            order("B", LocalDate.of(2025, 3, 5), "500", "0", "500", OrderStatus.OUT_FOR_DELIVERY, "Pending");
    // Prepaid partial — outstanding (customer owes remainder), not COD.
    private final OrderReportRecord partial =
            order("C", LocalDate.of(2025, 3, 10), "800", "300", "0", OrderStatus.APPROVED, "N/A");
    // Cancelled — excluded from dues and payments.
    private final OrderReportRecord cancelled =
            order("D", LocalDate.of(2025, 3, 1), "400", "0", "0", OrderStatus.CANCELLED, "N/A");

    private final List<OrderReportRecord> orders = List.of(paid, codPending, partial, cancelled);

    @Test
    void outstandingListsCollectibleDuesOldestFirstExcludingCancelled() {
        TabularData table = builder.build(ReportType.OUTSTANDING, orders, all);

        // Order column is first; only B (500) and C (500) are collectible dues.
        List<String> codes = table.rows().stream().map(r -> r.get(0)).toList();
        assertThat(codes).containsExactly("B", "C"); // oldest (Mar 5) before Mar 10
        // Balance Due column (index 7) = total − received.
        assertThat(table.rows().get(0).get(7)).isEqualTo("500");
        assertThat(table.rows().get(1).get(7)).isEqualTo("500");
        // "Days" outstanding is computed vs the latest order date (Mar 10) for an open range.
        assertThat(table.rows().get(0).get(4)).isEqualTo("5"); // Mar 5 → Mar 10
        assertThat(codes).doesNotContain("A", "D");
    }

    @Test
    void codRemittanceListsOnlyCourierPendingCod() {
        TabularData table = builder.build(ReportType.COD_REMITTANCE, orders, all);

        List<String> codes = table.rows().stream().map(r -> r.get(0)).toList();
        assertThat(codes).containsExactly("B");
        assertThat(table.rows().get(0).get(5)).isEqualTo("500"); // COD Amount column
    }

    @Test
    void paymentsSummarisesDailyMoneyExcludingCancelled() {
        TabularData table = builder.build(ReportType.PAYMENTS, orders, all);

        // Three active order dates: Mar 1 (A only — D cancelled/excluded), Mar 5, Mar 10.
        assertThat(table.rows()).hasSize(3);
        // Mar 1 row: 1 order, total 1000, received 1000, cod 0, outstanding 0.
        List<String> mar1 = table.rows().get(0);
        assertThat(mar1.get(0)).isEqualTo("2025-03-01");
        assertThat(mar1.get(1)).isEqualTo("1");
        assertThat(mar1.get(3)).isEqualTo("1000"); // amount received
        assertThat(mar1.get(5)).isEqualTo("0");    // outstanding
        // Mar 5 row (COD order): outstanding = 500 − 0 = 500.
        List<String> mar5 = table.rows().get(1);
        assertThat(mar5.get(0)).isEqualTo("2025-03-05");
        assertThat(mar5.get(5)).isEqualTo("500");
    }
}
