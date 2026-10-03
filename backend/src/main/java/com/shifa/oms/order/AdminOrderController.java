package com.shifa.oms.order;

import com.shifa.oms.audit.AuditActions;
import com.shifa.oms.audit.AuditService;
import com.shifa.oms.auth.AuthPrincipal;
import com.shifa.oms.auth.CurrentUserService;
import com.shifa.oms.auth.SalespersonScopeResolver;
import com.shifa.oms.common.PageRequests;
import com.shifa.oms.common.PageResponse;
import com.shifa.oms.courier.CourierAssignmentService;
import com.shifa.oms.courier.dto.AssignCourierRequest;
import com.shifa.oms.label.LabelService;
import com.shifa.oms.order.domain.PaymentStatus;
import com.shifa.oms.order.dto.ApprovalQueueItemResponse;
import com.shifa.oms.order.dto.ApproveOrderRequest;
import com.shifa.oms.order.dto.BulkActionResult;
import com.shifa.oms.order.dto.BulkOrderIdsRequest;
import com.shifa.oms.order.dto.BulkPreviewResponse;
import com.shifa.oms.order.dto.CancelOrderRequest;
import com.shifa.oms.order.dto.OrderResponse;
import com.shifa.oms.order.dto.OrderSummaryResponse;
import com.shifa.oms.order.dto.RejectOrderRequest;
import com.shifa.oms.order.dto.UpdateOrderRequest;
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
import org.springframework.web.bind.annotation.PutMapping;
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
    private final OrderService orderService;
    private final CourierAssignmentService courierAssignmentService;

    private final ChannelSummaryService channelSummaryService;
    private final OrderExportService orderExportService;

    public AdminOrderController(AdminOrderService adminOrderService,
                                BulkOrderService bulkOrderService,
                                LabelService labelService,
                                CurrentUserService currentUserService,
                                AuditService auditService,
                                SalespersonScopeResolver scopeResolver,
                                OrderService orderService,
                                CourierAssignmentService courierAssignmentService,
                                ChannelSummaryService channelSummaryService,
                                OrderExportService orderExportService) {
        this.adminOrderService = adminOrderService;
        this.bulkOrderService = bulkOrderService;
        this.labelService = labelService;
        this.currentUserService = currentUserService;
        this.auditService = auditService;
        this.scopeResolver = scopeResolver;
        this.orderService = orderService;
        this.courierAssignmentService = courierAssignmentService;
        this.channelSummaryService = channelSummaryService;
        this.orderExportService = orderExportService;
    }

    /**
     * ADMIN-only channel dashboard: order metrics split by origin channel
     * (portal vs Shopify) plus the combined total, over an optional date window,
     * so an admin can track and differentiate own-portal orders from the
     * auto-imported Shopify orders. Explicitly {@code hasRole('ADMIN')} (matching
     * the class default) so a salesperson/team lead/accountant can never see it.
     *
     * @param from inclusive lower-bound {@code created_at} date (yyyy-MM-dd), optional
     * @param to   inclusive upper-bound {@code created_at} date (yyyy-MM-dd), optional
     */
    @GetMapping("/channel-summary")
    @PreAuthorize("hasRole('ADMIN')")
    public com.shifa.oms.order.dto.ChannelSummaryResponse channelSummary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return channelSummaryService.summary(from, to);
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
    @PreAuthorize("hasAnyRole('ADMIN','ACCOUNTANT','SALESPERSON','TEAM_LEAD','CA')")
    public PageResponse<OrderSummaryResponse> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(required = false) String statusGroup,
            @RequestParam(required = false) PaymentStatus paymentStatus,
            @RequestParam(required = false) com.shifa.oms.order.OrderSource source,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) Long createdBy) {
        // Salespeople see only the orders they punched; a team lead sees the orders
        // punched by their assigned salespeople; admin/accountant see all. The set
        // is resolved server-side (never from a client filter) so it can't be spoofed.
        AuthPrincipal actor = currentUserService.requireCurrentUser();
        java.util.Collection<Long> creatorIds = scopeResolver.creatorScope(actor).orElse(null);
        // Drill-down: an ADMIN may narrow the list to a single salesperson's orders
        // (e.g. tapping a leaderboard / salespeople row → their orders). Only ADMIN
        // gets this client-supplied narrowing — every other role stays bound to its
        // own server-derived scope above, so this can't widen or escape it.
        if (createdBy != null && actor.role() == com.shifa.oms.auth.Role.ADMIN) {
            creatorIds = java.util.List.of(createdBy);
        }
        Pageable pageable = PageRequests.of(page, size, sort, SORT_WHITELIST, DEFAULT_SORT);
        // Parse leniently so a stale pre-collapse group key (e.g. PACKAGING/COMPLETED)
        // maps to the new group instead of 400ing.
        OrderStatusGroup group = OrderStatusGroup.from(statusGroup);
        // Optional exact source filter (e.g. only Shopify-imported orders).
        return PageResponse.of(
                adminOrderService.listOrders(
                        q, status, group, paymentStatus, from, to, pageable, creatorIds, source));
    }

    /**
     * Exports the CURRENT filtered + scoped Orders list as CSV or Excel
     * (list-export enhancement). Accepts the same filter params as {@link #list}
     * and applies the identical server-resolved salesperson/team-lead scope, so
     * the file is exactly what the caller sees on screen (never wider). Capped at
     * {@link OrderExportService#MAX_ROWS} rows. Streamed as an attachment.
     *
     * @param format {@code xlsx} (default) or {@code csv}
     */
    @GetMapping("/export")
    @PreAuthorize("hasAnyRole('ADMIN','ACCOUNTANT','SALESPERSON','TEAM_LEAD','CA')")
    public ResponseEntity<byte[]> export(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(required = false) String statusGroup,
            @RequestParam(required = false) PaymentStatus paymentStatus,
            @RequestParam(required = false) com.shifa.oms.order.OrderSource source,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String format,
            @RequestParam(required = false) Long createdBy) {
        AuthPrincipal actor = currentUserService.requireCurrentUser();
        java.util.Collection<Long> creatorIds = scopeResolver.creatorScope(actor).orElse(null);
        if (createdBy != null && actor.role() == com.shifa.oms.auth.Role.ADMIN) {
            creatorIds = java.util.List.of(createdBy);
        }
        OrderStatusGroup group = OrderStatusGroup.from(statusGroup);
        OrderExportService.ExportResult result = orderExportService.export(
                q, status, group, paymentStatus, from, to, creatorIds, source, format);
        auditService.record(AuditActions.ORDERS_EXPORTED, AuditActions.ENTITY_ORDER, null,
                "Exported orders list (" + result.filename() + ")");
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(result.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + result.filename() + "\"")
                .body(result.content());
    }

    /** The approval queue of pending-approval orders with review details (Req 9.1, 9.2). */
    @GetMapping("/approval-queue")
    public List<ApprovalQueueItemResponse> approvalQueue() {
        return adminOrderService.approvalQueue();
    }

    /**
     * Approve a pending order → Approved (Req 9.3). Optionally carries the
     * delivery method the admin has chosen/overridden for this order
     * (in-house-delivery feature) — an absent/empty body approves with the
     * order's existing delivery method unchanged.
     */
    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAnyRole('ADMIN','ACCOUNTANT')")
    public OrderResponse approve(@PathVariable Long id,
                                 @Valid @RequestBody(required = false) ApproveOrderRequest request) {
        AuthPrincipal admin = currentUserService.requireCurrentUser();
        String deliveryMethod = request != null ? request.deliveryMethod() : null;
        OrderResponse response = adminOrderService.approve(id, admin, deliveryMethod);
        auditService.record(AuditActions.ORDER_APPROVED, AuditActions.ENTITY_ORDER,
                String.valueOf(id), "Approved order " + response.orderCode());
        return response;
    }

    /** Reject a pending order with a required reason → Rejected (Req 9.4). */
    @PostMapping("/{id}/reject")
    public OrderResponse reject(@PathVariable Long id, @Valid @RequestBody RejectOrderRequest request) {
        AuthPrincipal admin = currentUserService.requireCurrentUser();
        OrderResponse response = adminOrderService.reject(id, request.category(), request.reason(), admin);
        auditService.record(AuditActions.ORDER_REJECTED, AuditActions.ENTITY_ORDER,
                String.valueOf(id), "Rejected order " + response.orderCode() + ": " + request.reason());
        return response;
    }

    /**
     * Cancel an order with a required note → Cancelled (order-cancellation
     * feature). Works at any pre-delivery stage — including after a QuikShipX
     * tracking id (AWB) has been generated — and, for a QuikShipX order, requests
     * cancellation at the courier so the pickup is aborted. 400 if the note is
     * blank; 409 if the order is already delivered/closed/returned (a delivered
     * order is a Return, not a Cancel). The service records its own detailed audit
     * (with the courier-cancel outcome).
     */
    @PostMapping("/{id}/cancel")
    public CancelOrderResponse cancel(@PathVariable Long id, @Valid @RequestBody CancelOrderRequest request) {
        AuthPrincipal admin = currentUserService.requireCurrentUser();
        AdminOrderService.CancelResult result = adminOrderService.cancel(id, request.note(), admin);
        return new CancelOrderResponse(result.order(), result.courierCancelAttempted(),
                result.courierCancelAccepted(), result.courierMessage());
    }

    /** API response for a cancellation: the cancelled order + courier-cancel outcome. */
    public record CancelOrderResponse(OrderResponse order, boolean courierCancelAttempted,
                                      boolean courierCancelAccepted, String courierMessage) {
    }

    /**
     * Admin edit-order (edit-order feature): corrects the customer / shipping /
     * line-item / lead-source / note / GSTIN / discount details a salesperson
     * entered. Re-prices the edited items through the same pricing engine used
     * at creation and reconciles tracked-product stock. Only allowed while the
     * order is still {@code Pending_Admin_Approval} or {@code Approved} — a 409
     * ({@code ORDER_NOT_EDITABLE}) is returned once fulfilment has begun.
     */
    @PutMapping("/{id}")
    public OrderResponse update(@PathVariable Long id, @Valid @RequestBody UpdateOrderRequest request) {
        AuthPrincipal admin = currentUserService.requireCurrentUser();
        // The field-level "what changed" ORDER_UPDATED audit is recorded inside
        // OrderService.updateOrder (co-located with the actual field mutation, so
        // it captures the real old→new diff). No duplicate bare audit here.
        return orderService.updateOrder(id, request, admin);
    }

    /**
     * Manually attaches a courier name + AWB to an order (ADMIN only; "assign
     * courier early" enhancement). Usable any time before dispatch so the
     * internal label's courier barcode can render as soon as staff know the
     * courier + AWB, rather than waiting for automatic in-house assignment
     * (which only runs after dispatch). Does not change the order's lifecycle
     * status.
     */
    @PostMapping("/{id}/assign-courier")
    public void assignCourier(@PathVariable Long id, @Valid @RequestBody AssignCourierRequest request) {
        courierAssignmentService.manuallyAssign(id, request.courierName(), request.awb());
        auditService.record(AuditActions.COURIER_MANUALLY_ASSIGNED, AuditActions.ENTITY_ORDER,
                String.valueOf(id),
                "Manually assigned courier " + request.courierName() + " (AWB " + request.awb() + ")");
    }

    /**
     * The known delivery partners (courier companies), alphabetical (delivery-
     * partner dropdown enhancement): backs the "Assign courier" modal's picker
     * so the admin selects a known partner (e.g. QuikShipX, Blue Dart) instead
     * of free-typing a name that could create a duplicate/typo'd company.
     */
    @GetMapping("/courier-companies")
    public List<com.shifa.oms.courier.dto.CourierCompanyResponse> courierCompanies() {
        return courierAssignmentService.listCompanies().stream()
                .map(com.shifa.oms.courier.dto.CourierCompanyResponse::from)
                .toList();
    }

    /** Read-only eligibility preview; final mutations still re-check each order. */
    @PostMapping("/bulk-preview")
    public BulkPreviewResponse bulkPreview(
            @RequestParam String action,
            @Valid @RequestBody BulkOrderIdsRequest request) {
        return bulkOrderService.preview(action, request.ids());
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
