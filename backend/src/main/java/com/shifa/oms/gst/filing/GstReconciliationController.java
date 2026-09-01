package com.shifa.oms.gst.filing;

import com.shifa.oms.gst.filing.dto.DrillDownRowResponse;
import com.shifa.oms.gst.filing.dto.ReconciliationSummaryResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The Phase-3 advisory <strong>GST returns reconciliation</strong> endpoints (GST returns &amp;
 * filing, Reqs 7, 8, 9). Ties a return period's figures back to the General Ledger control ledgers
 * and the P&amp;L revenue, and drills into the contributing orders / vouchers behind a compared
 * figure. Restricted at the class level to <strong>ADMIN and CA</strong> (Reqs 10.1, 10.6, 10.7);
 * a sibling of {@code GstFilingController} on the same {@code /api/ca/gst} tree, kept separate so the
 * {@code /reconciliation} base path is gated by one clear class-level rule.
 *
 * <p>Read-only and advisory: it posts nothing to the ledger and never blocks filing (Req 7.7). All
 * delegation is to {@link ReconciliationService}; unauthorized roles get a 403 and unauthenticated
 * requests a 401 (JSON via the existing {@code GlobalExceptionHandler}).
 */
@RestController
@RequestMapping("/api/ca/gst/reconciliation")
@PreAuthorize("hasAnyRole('ADMIN','CA')")
public class GstReconciliationController {

    private final ReconciliationService reconciliationService;

    public GstReconciliationController(ReconciliationService reconciliationService) {
        this.reconciliationService = reconciliationService;
    }

    /**
     * The reconciliation summary for a return period (Reqs 7.1, 8.1, 9.1, 9.2, 9.4–9.6): the five
     * compared figures against the GST control ledgers and the P&amp;L revenue, each marked reconciled
     * / unreconciled, plus whether the whole period reconciles. When {@code month}/{@code year} are
     * omitted the current month is used (from the service's clock).
     *
     * @param month the calendar month 1–12, or omitted for the current month
     * @param year  the four-digit calendar year, or omitted for the current year
     * @return the reconciliation summary (advisory — nothing is posted to the ledger)
     */
    @GetMapping
    public ReconciliationSummaryResponse reconcile(@RequestParam(required = false) Integer month,
                                                   @RequestParam(required = false) Integer year) {
        return ReconciliationSummaryResponse.from(reconciliationService.reconcile(month, year));
    }

    /**
     * The rows contributing to a compared figure for the period, for the reconciliation drill-down
     * (Reqs 9.3, 9.7): return-backed figures list the period's contributing orders; ledger-backed
     * figures list the period's posted voucher lines. An unknown figure or a figure with no
     * contributors yields an empty list, never an error.
     *
     * @param month  the calendar month, 1–12
     * @param year   the four-digit calendar year
     * @param figure the compared-figure key (e.g. {@code GSTR1_OUTPUT_TAX})
     * @return the contributing rows (may be empty)
     */
    @GetMapping("/drill-down")
    public List<DrillDownRowResponse> drillDown(@RequestParam int month, @RequestParam int year,
                                                @RequestParam String figure) {
        return DrillDownRowResponse.fromAll(reconciliationService.drillDown(month, year, figure));
    }
}
