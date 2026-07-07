package com.shifa.oms.reconciliation;

import com.shifa.oms.common.PageRequests;
import com.shifa.oms.common.PageResponse;
import com.shifa.oms.reconciliation.domain.ReceivableType;
import com.shifa.oms.reconciliation.dto.CourierSummaryResponse;
import com.shifa.oms.reconciliation.dto.ReceivableResponse;
import com.shifa.oms.reconciliation.dto.SegregationResponse;
import com.shifa.oms.reconciliation.dto.SettleReceivableRequest;
import com.shifa.oms.reconciliation.dto.UnsettledCodResponse;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * COD and loss reconciliation dashboard API (Req 17.4, 18.1&ndash;18.6).
 *
 * <p>All endpoints are restricted to {@code ACCOUNTANT} and {@code ADMIN}: the
 * reconciliation dashboard is financial data an accountant works with, and the
 * admin has full access (Req 5.4). The server is authoritative; the Angular
 * route guard only mirrors this for UX.
 *
 * <ul>
 *   <li>{@code GET /api/recon/receivables?courier=&type=} — list receivables,
 *       filterable by courier company and type (Req 18.1&ndash;18.3).</li>
 *   <li>{@code GET /api/recon/summary} — per-courier COD/claim outstanding totals
 *       (Req 18.1, 18.2, 18.6).</li>
 *   <li>{@code GET /api/recon/cod/unsettled} — delivered COD orders whose
 *       receivable is not yet settled (Req 18.3).</li>
 *   <li>{@code GET /api/recon/segregation} — prepaid vs COD segregation
 *       (Req 18.4).</li>
 *   <li>{@code GET /api/recon/claims/pending} — unsettled claims that need filing
 *       (Req 17.4).</li>
 *   <li>{@code POST /api/recon/receivables/&#123;id&#125;/settle} — mark a receivable
 *       settled, idempotently (Req 18.5).</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/recon")
@PreAuthorize("hasAnyRole('ADMIN','ACCOUNTANT')")
public class ReconciliationController {

    /** Whitelist of API sort fields → JPA properties for the receivables table. */
    private static final Map<String, String> SORT_WHITELIST = Map.of(
            "createdAt", "createdAt",
            "amount", "amount",
            "type", "type",
            "settled", "settled");

    private static final Sort DEFAULT_SORT =
            Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id"));

    private final ReconciliationService reconciliationService;

    public ReconciliationController(ReconciliationService reconciliationService) {
        this.reconciliationService = reconciliationService;
    }

    /**
     * List receivables, filterable by courier company and type (Req 18.1&ndash;18.3).
     *
     * <p>Retained as-is (returns a bare array) for backward compatibility with
     * existing callers; the Wave 2 paged table uses {@link #receivablesPage} instead.
     */
    @GetMapping("/receivables")
    public List<ReceivableResponse> receivables(
            @RequestParam(required = false) Long courier,
            @RequestParam(required = false) ReceivableType type) {
        return reconciliationService.listReceivables(courier, type);
    }

    /**
     * Server-side paged / sorted / filtered receivables list backing the Wave 2
     * reconciliation table (ROADMAP 2.2). Returns the {@link PageResponse} envelope.
     *
     * @param courier restrict to a courier company id (optional)
     * @param type    COD_RECEIVABLE / CLAIM_RECEIVABLE (optional)
     * @param settled restrict to settled/unsettled rows (optional)
     * @param q       free-text order match over code / customer / mobile / id / AWB (optional)
     * @param from    inclusive {@code created_at} lower-bound date, ISO {@code yyyy-MM-dd} (optional)
     * @param to      inclusive {@code created_at} upper-bound date, ISO {@code yyyy-MM-dd} (optional)
     * @param page    zero-based page index (default 0)
     * @param size    page size (default 20, capped at 100)
     * @param sort    {@code field,dir} — one of createdAt/amount/type/settled
     */
    @GetMapping("/receivables/page")
    public PageResponse<ReceivableResponse> receivablesPage(
            @RequestParam(required = false) Long courier,
            @RequestParam(required = false) ReceivableType type,
            @RequestParam(required = false) Boolean settled,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort) {
        Pageable pageable = PageRequests.of(page, size, sort, SORT_WHITELIST, DEFAULT_SORT);
        return PageResponse.of(
                reconciliationService.listReceivables(courier, type, settled, q, from, to, pageable));
    }

    /** Per-courier COD/claim outstanding summary (Req 18.1, 18.2, 18.6). */
    @GetMapping("/summary")
    public List<CourierSummaryResponse> summary() {
        return reconciliationService.perCourierSummary();
    }

    /** Delivered COD orders whose receivable is unsettled (Req 18.3). */
    @GetMapping("/cod/unsettled")
    public List<UnsettledCodResponse> unsettledCod() {
        return reconciliationService.unsettledCod();
    }

    /** Prepaid vs COD segregation of fulfilled orders (Req 18.4). */
    @GetMapping("/segregation")
    public SegregationResponse segregation() {
        return reconciliationService.segregation();
    }

    /** Unsettled claims that need filing against their courier/AWB (Req 17.4). */
    @GetMapping("/claims/pending")
    public List<ReceivableResponse> pendingClaims() {
        return reconciliationService.pendingClaims();
    }

    /** Mark a receivable settled with an optional date; idempotent (Req 18.5). */
    @PostMapping("/receivables/{id}/settle")
    public ReceivableResponse settle(
            @PathVariable Long id,
            @RequestBody(required = false) SettleReceivableRequest request) {
        return reconciliationService.settle(id, request != null ? request.date() : null);
    }
}
