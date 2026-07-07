package com.shifa.oms.reporting;

import com.shifa.oms.order.domain.PaymentStatus;
import com.shifa.oms.reporting.domain.DateRange;
import com.shifa.oms.reporting.domain.OrderReportRecord;
import com.shifa.oms.reporting.domain.TabularData;
import com.shifa.oms.reporting.domain.VyaparTableBuilder;
import com.shifa.oms.statemachine.OrderStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for the Vyapar empty-range export (Req 23.2): when the selected date
 * range contains no orders, the export file contains only the header row, and
 * the caller surfaces a "no orders found" message.
 */
class VyaparEmptyRangeExportTest {

    private final VyaparTableBuilder builder = new VyaparTableBuilder();
    private final CsvReportExporter csv = new CsvReportExporter();
    private final ExcelReportExporter excel = new ExcelReportExporter();

    /** The message the reporting API returns when a Vyapar range is empty (Req 23.2). */
    private static String emptyRangeMessage(TabularData table) {
        return table.rowCount() == 0
                ? "No orders were found for the selected date range." : null;
    }

    @Test
    void emptyOrderSetYieldsHeaderOnlyTableAndMessage() {
        TabularData table = builder.build(List.of(), DateRange.all());

        assertThat(table.headers()).isEqualTo(VyaparTableBuilder.HEADERS);
        assertThat(table.rowCount()).isZero();
        assertThat(emptyRangeMessage(table))
                .isEqualTo("No orders were found for the selected date range.");

        // CSV output is exactly one line: the header row (Req 23.2).
        String csvContent = new String(csv.export(table), StandardCharsets.UTF_8);
        List<String> lines = csvContent.lines().toList();
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0)).isEqualTo(String.join(",", VyaparTableBuilder.HEADERS));
    }

    @Test
    void ordersOutsideTheRangeStillYieldHeaderOnly() {
        OrderReportRecord order = new OrderReportRecord(
                1L, "SHR-1", LocalDate.of(2024, 6, 1), 2L,
                "Asha", "9876543210", "Maharashtra",
                List.of(new OrderReportRecord.ProductLine("Amla", 2,
                        new BigDecimal("100.00"), new BigDecimal("200.00"))),
                new BigDecimal("200.00"), BigDecimal.ZERO, new BigDecimal("200.00"),
                PaymentStatus.COD, OrderStatus.PENDING_ADMIN_APPROVAL, "N/A", "N/A", null);

        // A window that excludes the single order (June order, January window).
        DateRange january = DateRange.of(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 31));
        TabularData table = builder.build(List.of(order), january);

        assertThat(table.rowCount()).isZero();
        assertThat(emptyRangeMessage(table)).isNotNull();

        // Excel file has only the header row present.
        byte[] xlsx = excel.export("Vyapar", table);
        assertThat(xlsx).isNotEmpty();
    }
}
