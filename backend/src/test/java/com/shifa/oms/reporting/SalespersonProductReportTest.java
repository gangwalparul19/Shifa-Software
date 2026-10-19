package com.shifa.oms.reporting;

import com.shifa.oms.order.domain.PaymentStatus;
import com.shifa.oms.reporting.domain.DateRange;
import com.shifa.oms.reporting.domain.OrderReportRecord;
import com.shifa.oms.reporting.domain.OrderReportRecord.ProductLine;
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
 * Focused unit tests for the SALESPERSON_PRODUCT report (client request): per
 * salesperson, per product, the total quantity punched and the total sales.
 * Pure — no Spring/DB — over hand-built {@link OrderReportRecord}s. Verifies the
 * (salesperson, product) grouping, quantity + sales sums, the date window, the
 * revenue exclusions (rejected/cancelled dropped), and the ordering.
 */
class SalespersonProductReportTest {

    private final ReportTableBuilder builder = new ReportTableBuilder();
    private final DateRange all = new DateRange(null, null);

    private static ProductLine line(String name, int qty, String rate) {
        BigDecimal r = new BigDecimal(rate);
        return new ProductLine(name, qty, r, r.multiply(BigDecimal.valueOf(qty)));
    }

    private static OrderReportRecord order(long salespersonId, LocalDate date,
                                           OrderStatus status, ProductLine... lines) {
        return new OrderReportRecord(
                1L, "SHR-X", date, salespersonId, "Cust", "9800000000", "MH", List.of(lines),
                new BigDecimal("0"), new BigDecimal("0"), new BigDecimal("0"),
                PaymentStatus.FULLY_PAID, status, "N/A", "N/A", "AWB", null, null);
    }

    @Test
    void groupsBySalespersonAndProductSummingQuantityAndSales() {
        // Salesperson 7: Amla x2 @100 (two orders → qty 3, sales 300) + Neem x1 @250.
        // Salesperson 9: Amla x5 @100.
        List<OrderReportRecord> orders = List.of(
                order(7L, LocalDate.of(2026, 5, 1), OrderStatus.DELIVERED,
                        line("Amla Juice", 2, "100"), line("Neem Caps", 1, "250")),
                order(7L, LocalDate.of(2026, 5, 2), OrderStatus.APPROVED,
                        line("Amla Juice", 1, "100")),
                order(9L, LocalDate.of(2026, 5, 3), OrderStatus.DELIVERED,
                        line("Amla Juice", 5, "100")));

        TabularData table = builder.build(ReportType.SALESPERSON_PRODUCT, orders, all);

        assertThat(table.headers()).containsExactly("Salesperson", "Product", "Quantity", "Total Sales");
        // Salesperson id ascending (7 before 9); within 7, sales desc (Neem 250 > Amla 300? no:
        // Amla = 3×100 = 300, Neem = 250 → Amla first).
        List<List<String>> rows = table.rows();
        assertThat(rows).hasSize(3);

        // Row 0: salesperson 7, Amla Juice, qty 3, sales 300.
        assertThat(rows.get(0)).containsExactly("7", "Amla Juice", "3", "300.00");
        // Row 1: salesperson 7, Neem Caps, qty 1, sales 250.
        assertThat(rows.get(1)).containsExactly("7", "Neem Caps", "1", "250.00");
        // Row 2: salesperson 9, Amla Juice, qty 5, sales 500.
        assertThat(rows.get(2)).containsExactly("9", "Amla Juice", "5", "500.00");
    }

    @Test
    void excludesRejectedAndCancelledOrders() {
        List<OrderReportRecord> orders = List.of(
                order(7L, LocalDate.of(2026, 5, 1), OrderStatus.DELIVERED, line("Amla Juice", 2, "100")),
                order(7L, LocalDate.of(2026, 5, 1), OrderStatus.CANCELLED, line("Amla Juice", 9, "100")),
                order(7L, LocalDate.of(2026, 5, 1), OrderStatus.REJECTED, line("Amla Juice", 9, "100")),
                order(7L, LocalDate.of(2026, 5, 1), OrderStatus.PAYMENT_REJECTED, line("Amla Juice", 9, "100")));

        TabularData table = builder.build(ReportType.SALESPERSON_PRODUCT, orders, all);

        // Only the delivered order counts: qty 2, sales 200 (the 3 non-revenue orders dropped).
        assertThat(table.rows()).hasSize(1);
        assertThat(table.rows().get(0)).containsExactly("7", "Amla Juice", "2", "200.00");
    }

    @Test
    void respectsDateWindow() {
        List<OrderReportRecord> orders = List.of(
                order(7L, LocalDate.of(2026, 5, 1), OrderStatus.DELIVERED, line("Amla Juice", 2, "100")),
                order(7L, LocalDate.of(2026, 6, 1), OrderStatus.DELIVERED, line("Amla Juice", 5, "100")));

        // Window = May only → the June order (qty 5) is excluded.
        TabularData table = builder.build(ReportType.SALESPERSON_PRODUCT, orders,
                new DateRange(LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31)));

        assertThat(table.rows()).hasSize(1);
        assertThat(table.rows().get(0)).containsExactly("7", "Amla Juice", "2", "200.00");
    }

    @Test
    void bucketsNullSalespersonAsUnspecified() {
        OrderReportRecord noSalesperson = new OrderReportRecord(
                1L, "SHR-X", LocalDate.of(2026, 5, 1), null, "Cust", "9800000000", "MH",
                List.of(line("Amla Juice", 2, "100")),
                new BigDecimal("0"), new BigDecimal("0"), new BigDecimal("0"),
                PaymentStatus.FULLY_PAID, OrderStatus.DELIVERED, "N/A", "N/A", "AWB", null, null);

        TabularData table = builder.build(ReportType.SALESPERSON_PRODUCT, List.of(noSalesperson), all);

        assertThat(table.rows()).hasSize(1);
        assertThat(table.rows().get(0).get(0)).isEqualTo("UNSPECIFIED");
    }
}
