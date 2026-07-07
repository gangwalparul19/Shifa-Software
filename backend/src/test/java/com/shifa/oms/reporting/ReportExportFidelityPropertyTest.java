package com.shifa.oms.reporting;

import com.shifa.oms.order.domain.PaymentStatus;
import com.shifa.oms.reporting.domain.DateRange;
import com.shifa.oms.reporting.domain.OrderReportRecord;
import com.shifa.oms.reporting.domain.ReportTableBuilder;
import com.shifa.oms.reporting.domain.ReportType;
import com.shifa.oms.reporting.domain.TabularData;
import com.shifa.oms.reporting.domain.VyaparTableBuilder;
import com.shifa.oms.statemachine.OrderStatus;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.Size;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based test for report and export content fidelity.
 *
 * Feature: shifa-herbal-remedies, Property 24: Report and export content
 * fidelity. For ANY generated report, the salesperson-wise rows contain all
 * required columns, and each exported file (Excel, PDF, Vyapar CSV/Excel)
 * contains the same set of rows/columns as the displayed report. Tested at the
 * row-model level: the export builders produce the same rows/headers as the
 * report model (Excel and Vyapar CSV/Excel are round-tripped; PDF is compared
 * against the cell matrix it renders).
 *
 * Validates: Requirements 20.3, 20.4, 23.1
 */
class ReportExportFidelityPropertyTest {

    private static final LocalDate EPOCH = LocalDate.of(2024, 1, 1);

    private final ReportTableBuilder tableBuilder = new ReportTableBuilder();
    private final VyaparTableBuilder vyaparBuilder = new VyaparTableBuilder();
    private final ExcelReportExporter excel = new ExcelReportExporter();
    private final PdfReportExporter pdf = new PdfReportExporter();
    private final CsvReportExporter csv = new CsvReportExporter();

    // Feature: shifa-herbal-remedies, Property 24: Report and export content fidelity
    @Property(tries = 150)
    void exportsReproduceTheDisplayedReport(
            @ForAll @Size(max = 25) List<@net.jqwik.api.From("orders") OrderReportRecord> orders,
            @ForAll ReportType type) throws IOException {

        DateRange window = DateRange.all();
        TabularData table = tableBuilder.build(type, orders, window);

        // The salesperson-wise report carries every required column, and every row
        // has one cell per required column (Req 20.3).
        if (type == ReportType.SALESPERSON) {
            assertThat(table.headers()).isEqualTo(ReportTableBuilder.SALESPERSON_HEADERS);
            for (List<String> row : table.rows()) {
                assertThat(row).hasSameSizeAs(ReportTableBuilder.SALESPERSON_HEADERS);
            }
        }

        // Excel export round-trips to the same headers + rows (Req 20.4).
        TabularData fromExcel = readXlsx(excel.export(type.name(), table));
        assertThat(fromExcel.headers()).isEqualTo(table.headers());
        assertThat(fromExcel.rows()).isEqualTo(table.rows());

        // PDF export renders exactly the header + data rows, same columns (Req 20.4).
        List<List<String>> pdfMatrix = PdfReportExporter.toMatrix(table);
        assertThat(pdf.export("t", table)).isNotEmpty();
        assertThat(pdfMatrix.get(0)).isEqualTo(table.headers());
        assertThat(pdfMatrix.subList(1, pdfMatrix.size())).isEqualTo(table.rows());
    }

    // Feature: shifa-herbal-remedies, Property 24: Vyapar export fidelity
    @Property(tries = 150)
    void vyaparExportsReproduceTheVyaparTable(
            @ForAll @Size(max = 25) List<@net.jqwik.api.From("orders") OrderReportRecord> orders) throws IOException {
        DateRange window = DateRange.all();
        TabularData table = vyaparBuilder.build(orders, window);

        assertThat(table.headers()).isEqualTo(VyaparTableBuilder.HEADERS);

        // Vyapar CSV round-trips to the same headers + rows (Req 23.1).
        TabularData fromCsv = parseCsv(csv.export(table));
        assertThat(fromCsv.headers()).isEqualTo(table.headers());
        assertThat(fromCsv.rows()).isEqualTo(table.rows());

        // Vyapar Excel round-trips to the same headers + rows (Req 23.1).
        TabularData fromExcel = readXlsx(excel.export("Vyapar", table));
        assertThat(fromExcel.headers()).isEqualTo(table.headers());
        assertThat(fromExcel.rows()).isEqualTo(table.rows());
    }

    // --- Round-trip helpers -------------------------------------------------

    private static TabularData readXlsx(byte[] bytes) throws IOException {
        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = wb.getSheetAt(0);
            List<String> headers = new ArrayList<>();
            Row header = sheet.getRow(0);
            if (header != null) {
                for (int c = 0; c < header.getLastCellNum(); c++) {
                    headers.add(cellString(header.getCell(c)));
                }
            }
            List<List<String>> rows = new ArrayList<>();
            for (int r = 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                List<String> cells = new ArrayList<>();
                for (int c = 0; c < headers.size(); c++) {
                    cells.add(row == null ? "" : cellString(row.getCell(c)));
                }
                rows.add(cells);
            }
            return new TabularData(headers, rows);
        }
    }

    private static String cellString(Cell cell) {
        return cell == null ? "" : cell.getStringCellValue();
    }

    /** Minimal RFC-4180 CSV parser for the round-trip check. */
    private static TabularData parseCsv(byte[] bytes) {
        String content = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        List<List<String>> allRows = new ArrayList<>();
        List<String> current = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < content.length(); i++) {
            char ch = content.charAt(i);
            if (inQuotes) {
                if (ch == '"') {
                    if (i + 1 < content.length() && content.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    field.append(ch);
                }
            } else if (ch == '"') {
                inQuotes = true;
            } else if (ch == ',') {
                current.add(field.toString());
                field.setLength(0);
            } else if (ch == '\r') {
                // skip; handled by \n
            } else if (ch == '\n') {
                current.add(field.toString());
                field.setLength(0);
                allRows.add(current);
                current = new ArrayList<>();
            } else {
                field.append(ch);
            }
        }
        if (field.length() > 0 || !current.isEmpty()) {
            current.add(field.toString());
            allRows.add(current);
        }
        List<String> headers = allRows.isEmpty() ? List.of() : allRows.get(0);
        List<List<String>> rows = allRows.size() <= 1
                ? List.of() : allRows.subList(1, allRows.size());
        return new TabularData(headers, rows);
    }

    // --- Generators ---------------------------------------------------------

    @Provide
    Arbitrary<OrderReportRecord> orders() {
        Arbitrary<Integer> dayOffset = Arbitraries.integers().between(0, 240);
        Arbitrary<Long> salesperson = Arbitraries.longs().between(1, 4);
        Arbitrary<String> state = Arbitraries.of("Maharashtra", "Gujarat", "Delhi");
        Arbitrary<PaymentStatus> payment = Arbitraries.of(PaymentStatus.values());
        Arbitrary<OrderStatus> status = Arbitraries.of(OrderStatus.values());
        Arbitrary<List<OrderReportRecord.ProductLine>> lines =
                productLine().list().ofMinSize(1).ofMaxSize(4);
        Arbitrary<Long> id = Arbitraries.longs().between(1, 100000);

        return Combinators.combine(id, dayOffset, salesperson, state, payment, status, lines)
                .as((oid, off, sp, st, pay, os, ls) -> {
                    BigDecimal total = BigDecimal.ZERO;
                    for (OrderReportRecord.ProductLine l : ls) {
                        total = total.add(l.lineTotal());
                    }
                    BigDecimal received = pay == PaymentStatus.FULLY_PAID ? total : BigDecimal.ZERO;
                    BigDecimal cod = pay == PaymentStatus.FULLY_PAID ? BigDecimal.ZERO : total;
                    return new OrderReportRecord(
                            oid, "SHR-" + oid, EPOCH.plusDays(off), sp,
                            "Cust" + oid, "9000000000", st, ls,
                            total, received, cod, pay, os, "Pending", "N/A", "AWB" + oid);
                });
    }

    private Arbitrary<OrderReportRecord.ProductLine> productLine() {
        Arbitrary<String> name = Arbitraries.of("Amla", "Neem", "Tulsi", "Ashwagandha");
        Arbitrary<Integer> qty = Arbitraries.integers().between(1, 20);
        Arbitrary<Long> rate = Arbitraries.longs().between(1, 500);
        return Combinators.combine(name, qty, rate).as((n, q, r) -> {
            BigDecimal rateDec = BigDecimal.valueOf(r).setScale(2);
            BigDecimal lineTotal = rateDec.multiply(BigDecimal.valueOf(q));
            return new OrderReportRecord.ProductLine(n, q, rateDec, lineTotal);
        });
    }
}
