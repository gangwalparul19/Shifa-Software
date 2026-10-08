package com.shifa.oms.gst.filing;

import com.shifa.oms.audit.AuditActions;
import com.shifa.oms.audit.AuditService;
import com.shifa.oms.auth.CurrentUserService;
import com.shifa.oms.common.ApiException;
import com.shifa.oms.gst.Gstr1Exporter;
import com.shifa.oms.gst.domain.Gstr1Return;
import com.shifa.oms.gst.filing.dto.AmendmentResponse;
import com.shifa.oms.gst.filing.dto.AmendmentReviewRequest;
import com.shifa.oms.gst.filing.dto.CalendarResponse;
import com.shifa.oms.gst.filing.dto.FileReturnRequest;
import com.shifa.oms.gst.filing.dto.FilingStatusResponse;
import com.shifa.oms.gst.filing.dto.PrepareReturnRequest;
import com.shifa.oms.gst.filing.dto.ReopenReturnRequest;
import com.shifa.oms.gst.filing.dto.SnapshotResponse;
import com.shifa.oms.gst.filing.domain.AmendmentStatus;
import com.shifa.oms.gst.filing.domain.ReturnPeriod;
import com.shifa.oms.gst.filing.domain.ReturnType;
import com.shifa.oms.settings.SettingsService;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The Phase-3 GST returns &amp; filing endpoints: the filing-status lifecycle, the immutable filing
 * snapshot history, the due-date filing calendar, and the post-filing amendment queue
 * (GST returns &amp; filing, Reqs 1–5, 3, 4, 10). Restricted at the class level to <strong>ADMIN and
 * CA</strong> (Reqs 10.1, 10.6, 10.7) — ACCOUNTANT is intentionally excluded from these Phase-3
 * endpoints (the shared dashboard/report endpoints stay on {@code GstController}). Unauthorized roles
 * get a 403 and unauthenticated requests a 401, rendered as JSON by the existing
 * {@code GlobalExceptionHandler}; rejected transitions / locked periods / optimistic-lock collisions
 * surface as {@code 409 Conflict} from {@code FilingStatusService}.
 *
 * <p>Every figure is a thin delegation to the Epic-4 services; the acting user is resolved from
 * {@link CurrentUserService}. The base path is {@code /api/ca/gst} so the filing endpoints live under
 * {@code /api/ca/gst/filing/**} and the amendment endpoints under {@code /api/ca/gst/amendments}
 * (the exact URLs in the design's REST surface), both gated by the class-level rule.
 */
@RestController
@RequestMapping("/api/ca/gst")
@PreAuthorize("hasAnyRole('ADMIN','CA')")
public class GstFilingController {

    private final FilingStatusService filingStatusService;
    private final FilingSnapshotService filingSnapshotService;
    private final FilingCalendarService filingCalendarService;
    private final AmendmentService amendmentService;
    private final FilingAwareGstr1Provider filingAwareGstr1Provider;
    private final Gstr1Exporter gstr1Exporter;
    private final SettingsService settingsService;
    private final AuditService auditService;
    private final CurrentUserService currentUserService;

    public GstFilingController(FilingStatusService filingStatusService,
                               FilingSnapshotService filingSnapshotService,
                               FilingCalendarService filingCalendarService,
                               AmendmentService amendmentService,
                               FilingAwareGstr1Provider filingAwareGstr1Provider,
                               Gstr1Exporter gstr1Exporter,
                               SettingsService settingsService,
                               AuditService auditService,
                               CurrentUserService currentUserService) {
        this.filingStatusService = filingStatusService;
        this.filingSnapshotService = filingSnapshotService;
        this.filingCalendarService = filingCalendarService;
        this.amendmentService = amendmentService;
        this.filingAwareGstr1Provider = filingAwareGstr1Provider;
        this.gstr1Exporter = gstr1Exporter;
        this.settingsService = settingsService;
        this.auditService = auditService;
        this.currentUserService = currentUserService;
    }

    /**
     * The current filing status of GSTR-1 and GSTR-3B for a selected period (Reqs 1.2, 1.6). A
     * period/type with no filing row is reported {@code NOT_STARTED}.
     */
    @GetMapping("/filing/status")
    public FilingStatusResponse status(@RequestParam int month, @RequestParam int year) {
        return FilingStatusResponse.from(filingStatusService.status(month, year), month, year);
    }

    /**
     * Marks a return prepared: {@code NOT_STARTED → PREPARED} (Reqs 1.3, 1.7). Returns the period's
     * refreshed statuses so the caller can rebind both return types.
     */
    @PostMapping("/filing/prepare")
    public FilingStatusResponse prepare(@Valid @RequestBody PrepareReturnRequest request) {
        filingStatusService.prepare(
                new ReturnPeriod(request.month(), request.year()), request.returnType(), actor());
        return FilingStatusResponse.from(
                filingStatusService.status(request.month(), request.year()),
                request.month(), request.year());
    }

    /**
     * Files a prepared return: {@code PREPARED → FILED} (Reqs 1.4, 1.5, 1.8, 2.3, 5.1, 5.8). The
     * snapshot is captured before the status flips; an optional 1–50-char acknowledgement reference is
     * stored when supplied. Returns the period's refreshed statuses.
     */
    @PostMapping("/filing/file")
    public FilingStatusResponse file(@Valid @RequestBody FileReturnRequest request) {
        filingStatusService.file(
                new ReturnPeriod(request.month(), request.year()), request.returnType(),
                request.ackReference(), actor());
        return FilingStatusResponse.from(
                filingStatusService.status(request.month(), request.year()),
                request.month(), request.year());
    }

    /**
     * Reopens a filed return: {@code FILED → PREPARED}, ADMIN/CA only (Reqs 2.4, 2.6, 2.7, 2.8). The
     * prior filing snapshot is retained untouched (Req 2.5). Returns the period's refreshed statuses.
     */
    @PostMapping("/filing/reopen")
    public FilingStatusResponse reopen(@Valid @RequestBody ReopenReturnRequest request) {
        filingStatusService.reopen(
                new ReturnPeriod(request.month(), request.year()), request.returnType(), actor());
        return FilingStatusResponse.from(
                filingStatusService.status(request.month(), request.year()),
                request.month(), request.year());
    }

    /**
     * The filing-snapshot history for a period and return type — every stored version in filing order,
     * each with its version, actor, and timestamp (metadata only, Reqs 5.4, 5.7).
     */
    @GetMapping("/filing/snapshots")
    public List<SnapshotResponse> snapshots(@RequestParam int month, @RequestParam int year,
                                            @RequestParam ReturnType returnType) {
        return filingSnapshotService.history(new ReturnPeriod(month, year), returnType).stream()
                .map(SnapshotResponse::from)
                .toList();
    }

    /**
     * The GST filing calendar for an Indian financial year (Reqs 4.1–4.6): one entry per month ×
     * return type with its due date, status, and reminder / overdue / due-today flags.
     *
     * @param fy the calendar year in which the financial year starts (e.g. {@code 2025} for FY 2025-26)
     */
    @GetMapping("/filing/calendar")
    public CalendarResponse calendar(@RequestParam int fy) {
        return CalendarResponse.from(fy, filingCalendarService.calendar(fy));
    }

    /**
     * The <strong>filing-aware</strong> GSTR-1 export for a period, served from the single figure
     * source {@link FilingAwareGstr1Provider#forPeriod(ReturnPeriod)} — the immutable filing snapshot
     * when GSTR-1 is FILED, the freshly computed return otherwise (Reqs 6.3, 5.4). Rendering reuses the
     * shipped {@link Gstr1Exporter}: {@code csv} (default) returns a ZIP of the seven per-section CSVs
     * ({@code application/zip}); {@code json} returns the portal-schema JSON ({@code application/json}).
     * Empty sections are emitted header-only in CSV / rowless in JSON by the exporter (Req 6.5). The
     * download is named {@code gstr1-<MMYYYY>.<ext>}.
     *
     * <p>Before any artifact is produced, the seller GSTIN is pre-checked from
     * {@link SettingsService#getSettings()}; when it is missing or blank the request is rejected with a
     * {@code 400} and no partial artifact is written (Req 6.8). Each successful export records a
     * {@code GSTR1_EXPORTED} audit event capturing the actor, period, format, and timestamp within the
     * same request flow (Reqs 6.6, 10.2). The pre-existing {@code /api/ca/gst/gstr1/export} on
     * {@code GstController} is left unchanged.
     *
     * @param month  the calendar month (1–12)
     * @param year   the four-digit calendar year
     * @param format {@code csv} (section ZIP, default) or {@code json} (portal schema)
     * @return the export artifact with the appropriate content type and download filename
     */
    @GetMapping("/filing/gstr1/export")
    public ResponseEntity<byte[]> exportGstr1(@RequestParam int month, @RequestParam int year,
                                              @RequestParam(defaultValue = "csv") String format) {
        String sellerGstin = settingsService.getSettings().getGstin();
        if (sellerGstin == null || sellerGstin.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SELLER_GSTIN_MISSING",
                    "The seller GSTIN is not configured. Set it in Settings before exporting GSTR-1.");
        }

        ReturnPeriod period = new ReturnPeriod(month, year);
        Gstr1Return ret = filingAwareGstr1Provider.forPeriod(period);
        boolean json = "json".equalsIgnoreCase(format);

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
        String filename = "gstr1-" + period.portalFp() + "." + ext;

        auditService.record(AuditActions.GSTR1_EXPORTED, AuditActions.ENTITY_GST, period.portalFp(),
                "Exported GSTR-1 for period " + period.portalFp() + " as " + ext.toUpperCase(Locale.ROOT));

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(contentType)
                .body(body);
    }

    /**
     * The post-filing amendment queue (Req 3, read side), optionally filtered by {@code status} and/or
     * the Filed_Period {@code year} being corrected. When no {@code status} is supplied, amendments in
     * every routing status are returned (PENDING, ROUTED, MANUAL_REVIEW).
     *
     * @param year   optional Filed_Period year to filter by (the {@code originalPeriodYear})
     * @param status optional routing status to filter by
     * @return the matching amendments (may be empty)
     */
    @GetMapping("/amendments")
    public List<AmendmentResponse> amendments(@RequestParam(required = false) Integer year,
                                              @RequestParam(required = false) AmendmentStatus status) {
        List<ReturnAmendment> rows;
        if (status != null) {
            rows = amendmentService.byStatus(status);
        } else {
            rows = new ArrayList<>();
            for (AmendmentStatus s : AmendmentStatus.values()) {
                rows.addAll(amendmentService.byStatus(s));
            }
        }
        return rows.stream()
                .filter(a -> year == null || a.getOriginalPeriodYear() == year)
                .map(AmendmentResponse::from)
                .toList();
    }

    /**
     * Resolves a manual-review amendment by routing it into the CA-chosen amendment table and open
     * target period, moving it to ROUTED (ADMIN/CA only — Reqs 3.5, 3.7).
     *
     * @param id      the amendment to resolve
     * @param request the chosen amendment table + target period (+ optional note)
     * @return the resolved, now-ROUTED amendment
     */
    @PostMapping("/amendments/{id}/review")
    public AmendmentResponse reviewAmendment(@PathVariable Long id,
                                             @Valid @RequestBody AmendmentReviewRequest request) {
        return AmendmentResponse.from(amendmentService.review(
                id, request.amendmentTable(), request.targetYear(), request.targetMonth()));
    }

    /** The acting user identifier for audit / snapshot attribution (401 when unauthenticated). */
    private String actor() {
        return currentUserService.requireCurrentUser().username();
    }
}
