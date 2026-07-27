package com.shifa.oms.reporting;

import com.shifa.oms.common.ApiException;
import com.shifa.oms.reporting.domain.ReportType;
import com.shifa.oms.reporting.domain.TabularData;
import com.shifa.oms.reporting.dto.ReportResponse;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Reporting and export API (Req 20.1&ndash;20.4, 23.1, 23.2).
 *
 * <p>Report reads are open to {@code ADMIN}, {@code ACCOUNTANT}, and
 * {@code SALESPERSON}; the service scopes a salesperson to their own orders
 * automatically (Req 5.5), so the salesperson-wise report a salesperson sees
 * contains only their sales. The Vyapar billing export is financial and limited
 * to {@code ADMIN}/{@code ACCOUNTANT} (Req 23).
 *
 * <ul>
 *   <li>{@code GET /api/reports/&#123;type&#125;?from=&to=} &mdash; a report as JSON
 *       (type = daily|monthly|product|state|salesperson), restricted to the
 *       optional date window (Req 20.1&ndash;20.3).</li>
 *   <li>{@code GET /api/reports/export?type=&format=xlsx|pdf&from=&to=} &mdash; the
 *       same report as an Excel or PDF download (Req 20.4).</li>
 *   <li>{@code GET /api/reports/vyapar?from=&to=&format=csv|xlsx} &mdash; the
 *       Vyapar billing export; an empty window yields a header-only file plus an
 *       {@code X-Report-Message} "no orders" header (Req 23.1, 23.2).</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/reports")
public class ReportController {

    private static final MediaType XLSX =
            MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    private static final MediaType CSV = MediaType.parseMediaType("text/csv");

    private final ReportService reportService;
    private final ExcelReportExporter excelExporter;
    private final PdfReportExporter pdfExporter;
    private final CsvReportExporter csvExporter;

    public ReportController(ReportService reportService,
                            ExcelReportExporter excelExporter,
                            PdfReportExporter pdfExporter,
                            CsvReportExporter csvExporter) {
        this.reportService = reportService;
        this.excelExporter = excelExporter;
        this.pdfExporter = pdfExporter;
        this.csvExporter = csvExporter;
    }

    /** A report as JSON, restricted to the optional date window (Req 20.1&ndash;20.3). */
    @GetMapping("/{type}")
    @PreAuthorize("hasAnyRole('ADMIN','ACCOUNTANT','SALESPERSON')")
    public ReportResponse report(
            @PathVariable String type,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return reportService.generate(parseType(type), from, to);
    }

    /** The current report exported as an Excel or PDF file (Req 20.4). */
    @GetMapping("/export")
    @PreAuthorize("hasAnyRole('ADMIN','ACCOUNTANT','SALESPERSON')")
    public ResponseEntity<byte[]> export(
            @RequestParam String type,
            @RequestParam(defaultValue = "xlsx") String format,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        ReportType reportType = parseType(type);
        // Generate once so the PDF can carry the KPI summary alongside the table.
        ReportResponse report = reportService.generate(reportType, from, to);
        TabularData table = new TabularData(report.headers(), report.rows());
        String baseName = "report-" + reportType.name().toLowerCase();
        String label = reportLabel(reportType);
        String subtitle = rangeSubtitle(from, to);
        return switch (format.trim().toLowerCase()) {
            case "xlsx", "excel" -> fileResponse(
                    excelExporter.export(reportType.name(), table), XLSX, baseName + ".xlsx", null);
            case "pdf" -> fileResponse(
                    pdfExporter.export(label, subtitle, report.summary(), table),
                    MediaType.APPLICATION_PDF, baseName + ".pdf", null);
            default -> throw new ApiException(HttpStatus.BAD_REQUEST, "UNSUPPORTED_FORMAT",
                    "Unsupported export format: " + format + " (expected xlsx or pdf).");
        };
    }

    /** The Vyapar billing export for a date range (Req 23.1, 23.2). */
    @GetMapping("/vyapar")
    @PreAuthorize("hasAnyRole('ADMIN','ACCOUNTANT')")
    public ResponseEntity<byte[]> vyapar(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "csv") String format) {
        TabularData table = reportService.vyaparTable(from, to);
        // Empty range → header-only file plus a "no orders found" message (Req 23.2).
        String message = table.rowCount() == 0
                ? "No orders were found for the selected date range." : null;
        return switch (format.trim().toLowerCase()) {
            case "csv" -> fileResponse(
                    csvExporter.export(table), CSV, "vyapar-billing.csv", message);
            case "xlsx", "excel" -> fileResponse(
                    excelExporter.export("Vyapar", table), XLSX, "vyapar-billing.xlsx", message);
            default -> throw new ApiException(HttpStatus.BAD_REQUEST, "UNSUPPORTED_FORMAT",
                    "Unsupported Vyapar format: " + format + " (expected csv or xlsx).");
        };
    }

    private ResponseEntity<byte[]> fileResponse(byte[] body, MediaType contentType,
                                                String filename, String message) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
                .contentType(contentType)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"");
        if (message != null) {
            builder.header("X-Report-Message", message);
        }
        return builder.body(body);
    }

    private static ReportType parseType(String type) {
        try {
            return ReportType.from(type);
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "UNKNOWN_REPORT_TYPE", e.getMessage());
        }
    }

    /** A human report title used as the PDF heading (e.g. "Customer-wise Report"). */
    private static String reportLabel(ReportType type) {
        return switch (type) {
            case DAILY -> "Daily Sales Report";
            case MONTHLY -> "Monthly Sales Report";
            case PRODUCT -> "Product-wise Report";
            case STATE -> "State-wise Report";
            case CUSTOMER -> "Customer-wise Report";
            case SALESPERSON -> "Salesperson-wise Report";
            case ORDERS_BY_LEAD_SOURCE -> "Orders by Lead Source";
            case ORDERS_BY_STATUS -> "Orders by Status";
            case ORDERS_BY_SALESPERSON -> "Orders by Salesperson";
            case DELIVERY_OUTCOME -> "Delivery Outcome Report";
            case PAYMENTS -> "Daily Payments Report";
            case OUTSTANDING -> "Outstanding Dues Report";
            case COD_REMITTANCE -> "COD Pending from Courier";
            case EXPENSES -> "Expenses Report";
            case PURCHASE_ORDERS -> "Purchase Orders Report";
            case RETURNS -> "Returns & Refunds Report";
            case STOCK -> "Stock Movements Report";
        };
    }

    private static final DateTimeFormatter RANGE_FMT = DateTimeFormatter.ofPattern("dd MMM yyyy");

    /** A friendly date-range subtitle for the PDF header (e.g. "01 Jun 2026 to 30 Jun 2026"). */
    private static String rangeSubtitle(LocalDate from, LocalDate to) {
        if (from == null && to == null) {
            return "All time";
        }
        if (from != null && to != null) {
            return from.format(RANGE_FMT) + " to " + to.format(RANGE_FMT);
        }
        return from != null ? "From " + from.format(RANGE_FMT) : "Up to " + to.format(RANGE_FMT);
    }
}
