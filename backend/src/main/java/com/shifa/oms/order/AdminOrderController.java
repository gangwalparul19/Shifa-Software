package com.shifa.oms.order;

import com.shifa.oms.audit.AuditActions;
import com.shifa.oms.audit.AuditService;
import com.shifa.oms.auth.AuthPrincipal;
import com.shifa.oms.auth.CurrentUserService;
import com.shifa.oms.auth.SalespersonScopeResolver;
import com.shifa.oms.common.PageRequests;
import com.shifa.oms.common.PageResponse;
import com.shifa.oms.label.LabelService;
import com.shifa.oms.order.domain.PaymentStatus;
import com.shifa.oms.order.dto.ApprovalQueueItemResponse;
import com.shifa.oms.order.dto.BulkActionResult;
import com.shifa.oms.order.dto.BulkOrderIdsRequest;
import com.shifa.oms.order.dto.OrderResponse;
import com.shifa.oms.order.dto.OrderSummaryResponse;
import com.shifa.oms.order.dto.RejectOrderRequest;
import com.shifa.oms.statemachine.OrderStatus;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
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

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Admin order-approval, listing, and bulk-action endpoints (Req 9; ROADMAP 2.2
 * "Wave 2").
 *
 * <p>Restricted to the {@code ADMIN} role via method security; unauthenticated
 * callers get 401 and non-admins 403. The queue lists pending-approval orders
 * with the review details needed on screen (Req 9.1, 9.2); approve/reject route
 * through the state machine so only legal transitions apply (409 otherwise), and
 * reject requires a non-blank reason (400 otherwise, Req 9.3, 9.4).
 *
 * <p>Wave 2 adds a server-side paged/sorted/filtered orders table
 * ({@code GET /api/admin/orders}) plus bulk actions (approve / mark-packed /
 * label generation) that reuse the existing single-order services so the order
 * state machine is never bypassed.
 */
@RestController
@RequestMapping("/api/admin/orders")
@PreAuthorize("hasRole('ADMIN')")
public class AdminOrderController {

    /** Whitelist of API sort fields → JPA properties for the orders table. */
    private static final Map<String, String> SORT_WHITELIST = Map.of(
            "createdAt", "createdAt",
            "orderCode", "orderCode",
            "customerName", "customerName",
            "totalAmount", "totalAmount",
            "orderStatus", "orderStatus",
            "paymentStatus", "paymentStatus");

    private static final Sort DEFAULT_SORT = Sort.by(Sort.Direction.DESC, "createdAt");

    private final AdminOrderService adminOrderService;
    private final BulkOrderService bulkOrderService;
    private final LabelService labelService;
    private final CurrentUserService currentUserService;
    private final AuditService auditService;
    private final SalespersonScopeResolver scopeResolver;

    public AdminOrderController(AdminOrderService adminOrderService,
                                BulkOrderService bulkOrderService,
                                LabelService labelService,
                                CurrentUserService currentUserService,
                                AuditService auditService,
                                SalespersonScopeResolver scopeResolver) {
        this.adminOrderService = adminOrderService;
        this.bulkOrderService = bulkOrderService;
        this.labelService = labelService;
        this.currentUserService = currentUserService;
        this.auditService = auditService;
        this.scopeResolver = scopeResolver;
    }

    /**
     * Server-side paged / sorted / filtered orders list backing the Wave 2 admin
     * table (ROADMAP 2.2). Returns the {@link PageResponse} envelope.
     *
     * @param q             substring over order code / customer name / mobile (optional)
     * @param status        exact order lifecycle status (optional)
     * @param paymentStatus exact payment status (optional)
     * @param from          inclusive {@code created_at} lower-bound date, ISO {@code yyyy-MM-dd} (optional)
     * @param to            inclusive {@code created_at} upper-bound date, ISO {@code yyyy-MM-dd} (optional)
     * @param page          zero-based page index (default 0)
     * @param size          page size (default 20, capped at 100)
     * @param sort          {@code field,dir} — one of createdAt/orderCode/customerName/totalAmount/orderStatus/paymentStatus
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','ACCOUNTANT','SALESPERSON','TEAM_LEAD')")
    public PageResponse<OrderSummaryResponse> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(required = false) OrderStatusGroup statusGroup,
            @RequestParam(required = false) PaymentStatus paymentStatus,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort) {
        // Salespeople see only the orders they punched; a team lead sees the orders
        // punched by their assigned salespeople; admin/accountant see all. The set
        // is resolved server-side (never from a client filter) so it can't be spoofed.
        AuthPrincipal actor = currentUserService.requireCurrentUser();
        java.util.Collection<Long> creatorIds = scopeResolver.creatorScope(actor).orElse(null);
        Pageable pageable = PageRequests.of(page, size, sort, SORT_WHITELIST, DEFAULT_SORT);
        return PageResponse.of(
                adminOrderService.listOrders(
                        q, status, statusGroup, paymentStatus, from, to, pageable, creatorIds));
    }

    /** The approval queue of pending-approval orders with review details (Req 9.1, 9.2). */
    @GetMapping("/approval-queue")
    public List<ApprovalQueueItemResponse> approvalQueue() {
        return adminOrderService.approvalQueue();
    }

    /** Approve a pending order → Approved (Req 9.3). */
    @PostMapping("/{id}/approve")
    public OrderResponse approve(@PathVariable Long id) {
        AuthPrincipal admin = currentUserService.requireCurrentUser();
        OrderResponse response = adminOrderService.approve(id, admin);
        auditService.record(AuditActions.ORDER_APPROVED, AuditActions.ENTITY_ORDER,
                String.valueOf(id), "Approved order " + response.orderCode());
        return response;
    }

    /** Reject a pending order with a required reason → Rejected (Req 9.4). */
    @PostMapping("/{id}/reject")
    public OrderResponse reject(@PathVariable Long id, @Valid @RequestBody RejectOrderRequest request) {
        AuthPrincipal admin = currentUserService.requireCurrentUser();
        OrderResponse response = adminOrderService.reject(id, request.reason(), admin);
        auditService.record(AuditActions.ORDER_REJECTED, AuditActions.ENTITY_ORDER,
                String.valueOf(id), "Rejected order " + response.orderCode() + ": " + request.reason());
        return response;
    }

    /**
     * Bulk-approve the given pending orders (ROADMAP 2.2). Each eligible order is
     * approved via the existing approval service (state machine + auto label);
     * ineligible ids are reported in {@code skipped} with a reason so the UI can
     * show partial success. Response: {@code { succeeded: [...], skipped: [{id, reason}] }}.
     */
    @PostMapping("/bulk-approve")
    public BulkActionResult bulkApprove(@Valid @RequestBody BulkOrderIdsRequest request) {
        AuthPrincipal admin = currentUserService.requireCurrentUser();
        BulkActionResult result = bulkOrderService.bulkApprove(request.ids(), admin);
        result.succeeded().forEach(id -> auditService.record(
                AuditActions.ORDER_APPROVED, AuditActions.ENTITY_ORDER,
                String.valueOf(id), "Bulk-approved order " + id));
        return result;
    }

    /**
     * Bulk mark-as-packed for orders currently in {@code Label_Generated}
     * (ROADMAP 2.2). Reuses the packing scan transition per id; ineligible ids
     * are reported in {@code skipped}. Response shape matches bulk-approve.
     */
    @PostMapping("/bulk-mark-packed")
    public BulkActionResult bulkMarkPacked(@Valid @RequestBody BulkOrderIdsRequest request) {
        AuthPrincipal actor = currentUserService.requireCurrentUser();
        return bulkOrderService.bulkMarkPacked(request.ids(), actor);
    }

    /**
     * Bulk internal-label generation (ROADMAP 2.2): returns a single merged PDF
     * containing one internal-label block per requested order, reusing the
     * existing label PDF generator. Streamed as an {@code application/pdf}
     * attachment. An unknown order id yields a 404.
     */
    @PostMapping("/bulk-labels")
    public ResponseEntity<byte[]> bulkLabels(@Valid @RequestBody BulkOrderIdsRequest request) {
        byte[] pdf = labelService.bulkInternalLabelPdf(request.ids());
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"order-labels.pdf\"")
                .body(pdf);
    }
}
