package com.shifa.oms.gst;

import com.shifa.oms.gst.dto.GstDashboardResponse;
import com.shifa.oms.gst.dto.GstOrderRow;
import com.shifa.oms.gst.dto.GstReportResponse;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

/**
 * CA GST / accounting endpoints (CA GST dashboard, Reqs 1, 6, 7, 8). Restricted
 * to ADMIN / CA / ACCOUNTANT; figures derive from immutable order tax snapshots
 * + the seller's GST settings. Default period is the current month.
 */
@RestController
@RequestMapping("/api/ca/gst")
@PreAuthorize("hasAnyRole('ADMIN','CA','ACCOUNTANT')")
public class GstController {

    private final GstAccountingService service;
    private final GstPdfExporter pdfExporter;

    public GstController(GstAccountingService service, GstPdfExporter pdfExporter) {
        this.service = service;
        this.pdfExporter = pdfExporter;
    }

    /** The CA dashboard: GST report + money in/out for the period (Req 6). */
    @GetMapping("/dashboard")
    public GstDashboardResponse dashboard(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return service.dashboard(from, to);
    }

    /** The filing-ready outward GST report for the period (Reqs 4, 5). */
    @GetMapping("/report")
    public GstReportResponse report(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return service.report(from, to);
    }

    /** The GST report exported as CSV (default) or a branded PDF (Req 7). */
    @GetMapping("/report/export")
    public ResponseEntity<byte[]> export(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(name = "format", defaultValue = "csv") String format) {
        GstReportResponse report = service.report(from, to);
        boolean pdf = "pdf".equalsIgnoreCase(format);
        byte[] body = pdf
                ? pdfExporter.toPdf(report)
                : GstReportExporter.toCsv(report).getBytes(StandardCharsets.UTF_8);
        String ext = pdf ? "pdf" : "csv";
        String filename = "gst-report-" + report.from() + "-to-" + report.to() + "." + ext;
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(pdf ? MediaType.APPLICATION_PDF : MediaType.parseMediaType("text/csv"))
                .body(body);
    }

    /**
     * Drill-down: the orders behind a GST figure for the period, optionally
     * filtered by place-of-supply {@code state}, a line {@code rate}, and/or a
     * line {@code hsn} — so the CA can track every order and its remaining dues.
     */
    @GetMapping("/orders")
    public List<GstOrderRow> orders(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) BigDecimal rate,
            @RequestParam(required = false) String hsn) {
        return service.orders(from, to, state, rate, hsn);
    }
}
