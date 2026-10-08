package com.shifa.oms.gst;

import com.shifa.oms.audit.AuditActions;
import com.shifa.oms.audit.AuditService;
import com.shifa.oms.gst.domain.Gstr1Return;
import com.shifa.oms.gst.dto.GstDashboardResponse;
import com.shifa.oms.gst.dto.GstOrderRow;
import com.shifa.oms.gst.dto.GstReportResponse;
import com.shifa.oms.gst.dto.Gstr1ReturnResponse;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
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
    private final Gstr1ReturnService gstr1ReturnService;
    private final Gstr1Exporter gstr1Exporter;
    private final AuditService auditService;

    public GstController(GstAccountingService service, GstPdfExporter pdfExporter,
                         Gstr1ReturnService gstr1ReturnService, Gstr1Exporter gstr1Exporter,
                         AuditService auditService) {
        this.service = service;
        this.pdfExporter = pdfExporter;
        this.gstr1ReturnService = gstr1ReturnService;
        this.gstr1Exporter = gstr1Exporter;
        this.auditService = auditService;
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

    /**
     * The portal-ready GSTR-1 return for the period (GST filing compliance, Reqs 1–6): the seven
     * section row lists, the unresolved place-of-supply flags, and a reconciliation of the same
     * period's totals to the CA GST dashboard. Default period = current month.
     */
    @GetMapping("/gstr1")
    public Gstr1ReturnResponse gstr1(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return Gstr1ReturnResponse.from(gstr1ReturnService.build(from, to));
    }

    /**
     * The GSTR-1 return exported for the GST portal / Offline Tool (Req 5.1, 5.3): {@code csv}
     * (default) returns a ZIP of the per-section CSVs ({@code application/zip}); {@code json} returns
     * the portal-schema JSON ({@code application/json}). The download is named {@code gstr1-<MMYYYY>}.
     * Every export writes a {@code GSTR1_EXPORTED} audit event capturing the actor + period (Req 13.3).
     */
    @GetMapping("/gstr1/export")
    public ResponseEntity<byte[]> exportGstr1(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(name = "format", defaultValue = "csv") String format) {
        Gstr1Return ret = gstr1ReturnService.build(from, to);
        boolean json = "json".equalsIgnoreCase(format);
        String period = String.format(Locale.ROOT, "%02d%04d", ret.month(), ret.year());

        byte[] body;
        MediaType contentType;
        String ext;
        if (json) {
            body = gstr1Exporter.toPortalJson(ret).getBytes(StandardCharsets.UTF_8);
            contentType = MediaType.APPLICATION_JSON;
            ext = "json";
        } else {
            body = gstr1Exporter.toZip(ret);
            contentType = MediaType.parseMediaType("application/zip");
            ext = "zip";
        }
        String filename = "gstr1-" + period + "." + ext;

        auditService.record(AuditActions.GSTR1_EXPORTED, AuditActions.ENTITY_GST, period,
                "Exported GSTR-1 for period " + period + " as " + ext.toUpperCase(Locale.ROOT));

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(contentType)
                .body(body);
    }
}
