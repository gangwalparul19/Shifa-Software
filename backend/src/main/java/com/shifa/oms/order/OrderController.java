package com.shifa.oms.order;

import com.shifa.oms.auth.AuthPrincipal;
import com.shifa.oms.auth.CurrentUserService;
import com.shifa.oms.auth.Role;
import com.shifa.oms.common.ValidationException;
import com.shifa.oms.invoice.InvoiceService;
import com.shifa.oms.order.dto.CreateOrderRequest;
import com.shifa.oms.order.dto.CustomerPrefillResponse;
import com.shifa.oms.order.dto.DuplicateCheckResponse;
import com.shifa.oms.order.dto.MarkDeliveredRequest;
import com.shifa.oms.order.dto.OrderResponse;
import com.shifa.oms.order.dto.OrderSummaryResponse;
import com.shifa.oms.order.dto.PaymentScreenshotResponse;
import com.shifa.oms.order.dto.ScreenshotUploadResponse;
import com.shifa.oms.order.dto.UpdateDeliveryStatusRequest;
import com.shifa.oms.order.dto.UpdateOrderRequest;
import com.shifa.oms.platform.storage.StorageService;
import com.shifa.oms.product.ProductService;
import com.shifa.oms.product.dto.ProductResponse;
import jakarta.validation.Valid;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

/**
 * Salesperson order-entry, search, duplicate detection, and payment-tracking
 * endpoints (Req 7, 21, 22). All routes require authentication; method security
 * restricts each to the roles permitted by the authority matrix. Salesperson
 * scoping (Req 5.5) is applied inside {@link OrderService}.
 */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;
    private final CurrentUserService currentUserService;
    private final InvoiceService invoiceService;
    private final ProductService productService;
    private final OrderSuggestionService suggestionService;
    private final ManualDeliveryService manualDeliveryService;

    public OrderController(OrderService orderService, CurrentUserService currentUserService,
                           InvoiceService invoiceService, ProductService productService,
                           OrderSuggestionService suggestionService,
                           ManualDeliveryService manualDeliveryService) {
        this.orderService = orderService;
        this.currentUserService = currentUserService;
        this.invoiceService = invoiceService;
        this.productService = productService;
        this.suggestionService = suggestionService;
        this.manualDeliveryService = manualDeliveryService;
    }

    /**
     * Published products for the order-entry product picker, optionally filtered
     * by a name/SKU substring {@code q} (Req 7.1, 7.2). Scoped to the order-entry
     * roles (SALESPERSON/ADMIN) — this replaces the retired public catalog
     * endpoint the picker previously used, now that the storefront is gone. Only
     * published products are offered, which is exactly what a salesperson should
     * be able to punch.
     */
    @GetMapping("/products")
    @PreAuthorize("hasAnyRole('SALESPERSON','ADMIN','TEAM_LEAD')")
    public List<ProductResponse> products(@RequestParam(name = "q", required = false) String q) {
        return productService.search(q);
    }

    /**
     * Best-selling products for the order-entry "favorites" quick-add. A
     * SALESPERSON gets their own best-sellers; an ADMIN gets business-wide.
     */
    @GetMapping("/products/top")
    @PreAuthorize("hasAnyRole('SALESPERSON','ADMIN','TEAM_LEAD')")
    public List<ProductResponse> topProducts(@RequestParam(name = "limit", defaultValue = "8") int limit) {
        AuthPrincipal actor = currentUserService.requireCurrentUser();
        Long createdBy = actor.role() == Role.SALESPERSON ? actor.userId() : null;
        return suggestionService.topProducts(createdBy, limit);
    }

    /**
     * Products frequently bought together with the given cart products (upsell).
     * {@code productIds} is a comma-separated list of the items already added.
     */
    @GetMapping("/products/related")
    @PreAuthorize("hasAnyRole('SALESPERSON','ADMIN','TEAM_LEAD')")
    public List<ProductResponse> relatedProducts(
            @RequestParam(name = "productIds") List<Long> productIds,
            @RequestParam(name = "limit", defaultValue = "3") int limit) {
        return suggestionService.relatedProducts(productIds, limit);
    }

    /** Punch a new salesperson order (Req 7.1-7.11). */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('SALESPERSON','ADMIN','TEAM_LEAD')")
    public OrderResponse create(@Valid @RequestBody CreateOrderRequest request) {
        AuthPrincipal actor = currentUserService.requireCurrentUser();
        return orderService.createSalespersonOrder(request, actor);
    }

    /**
     * Rework a REJECTED / PAYMENT_REJECTED order back into the approval queue
     * (rejection-status rework feature): the creating salesperson (or an admin)
     * fixes the flagged details and resubmits. The order moves back to
     * {@code Pending_Admin_Approval} with the same code + full history. Own-order
     * scoped inside {@link OrderService#resubmit} (a salesperson can only resubmit
     * an order they created; anything else is a 404); a non-rejected order yields
     * a 400.
     */
    @PostMapping("/{id}/resubmit")
    @PreAuthorize("hasAnyRole('SALESPERSON','ADMIN','TEAM_LEAD')")
    public OrderResponse resubmit(@PathVariable Long id, @Valid @RequestBody UpdateOrderRequest request) {
        AuthPrincipal actor = currentUserService.requireCurrentUser();
        return orderService.resubmit(id, request, actor);
    }

    /**
     * Edit an order the caller punched, while it is still awaiting approval
     * (own-pending-edit feature): the salesperson / team lead corrects the
     * customer / shipping / line-item / lead-source / note / GSTIN / discount
     * details (a change from the customer or the agent before an admin reviews it).
     * Own-order scoped inside {@link OrderService#updateOwnOrder} (a salesperson
     * can only edit an order they created; a team lead their team's — anything else
     * is a 404), and only while {@code Pending_Admin_Approval} (a 400 once approved,
     * directing to an admin). Records a field-level audit entry. Payment capture is
     * not editable here.
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('SALESPERSON','ADMIN','TEAM_LEAD')")
    public OrderResponse update(@PathVariable Long id, @Valid @RequestBody UpdateOrderRequest request) {
        AuthPrincipal actor = currentUserService.requireCurrentUser();
        return orderService.updateOwnOrder(id, request, actor);
    }

    /**
     * Manual one-shot "Mark Delivered" for an in-house (non-QuikShipX) order:
     * jumps the order straight to {@code Delivered} and settles it (Closed /
     * COD_Collected) in the same action (in-house-delivery feature). Restricted
     * to ADMIN, PACKING_USER, and the order's own salesperson — a salesperson
     * calling on another salesperson's order gets a 403 (enforced in the
     * service, since {@code TransitionAuthority} has no per-order ownership
     * concept).
     */
    @PostMapping("/{id}/mark-delivered")
    @PreAuthorize("hasAnyRole('ADMIN','PACKING_USER','SALESPERSON')")
    public OrderResponse markDelivered(@PathVariable Long id,
                                       @Valid @RequestBody(required = false) MarkDeliveredRequest request) {
        AuthPrincipal actor = currentUserService.requireCurrentUser();
        return manualDeliveryService.markDelivered(id, actor, request);
    }

    /**
     * Manually advance an in-house (non-QuikShipX) order's delivery status
     * (in-house-delivery feature): an in-house order has no courier partner, so
     * no webhook/poll ever reports progress — staff move it through Dispatched /
     * In_Transit / Out_For_Delivery and finally Delivered (which also settles it)
     * or a failure outcome. Optionally records/updates the vehicle reference
     * (bus vehicle no., train no., own van) in the same call.
     *
     * <p>Restricted to ADMIN, PACKING_USER, and the order's own salesperson (the
     * in-house-only and ownership checks are enforced in the service). A
     * non-in-house order yields 400; an illegal hop from the current status
     * yields 409.
     */
    @PostMapping("/{id}/delivery-status")
    @PreAuthorize("hasAnyRole('ADMIN','PACKING_USER','SALESPERSON')")
    public OrderResponse updateDeliveryStatus(
            @PathVariable Long id,
            @Valid @RequestBody UpdateDeliveryStatusRequest request) {
        AuthPrincipal actor = currentUserService.requireCurrentUser();
        return manualDeliveryService.updateDeliveryStatus(id, actor, request);
    }

    /**
     * Upload a payment screenshot, returning a storage key to attach to a
     * subsequent order (two-step upload, Req 7.6, 7.11).
     */
    @PostMapping(path = "/payment-screenshots", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('SALESPERSON','ADMIN','TEAM_LEAD')")
    public ScreenshotUploadResponse uploadScreenshot(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ValidationException("A payment screenshot file is required.");
        }
        try {
            String key = orderService.storePaymentScreenshot(
                    file.getOriginalFilename(), file.getContentType(), file.getBytes());
            return new ScreenshotUploadResponse(key);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read uploaded screenshot", e);
        }
    }

    /** Search orders by name / mobile / order code / id / AWB, role-scoped (Req 22.1). */
    @GetMapping
    @PreAuthorize("hasAnyRole('SALESPERSON','ACCOUNTANT','ADMIN','TEAM_LEAD','CA')")
    public List<OrderSummaryResponse> search(
            @RequestParam(name = "search", required = false) String search) {
        AuthPrincipal actor = currentUserService.requireCurrentUser();
        return orderService.search(search, actor);
    }

    /**
     * Whether prior orders exist for a mobile number (Req 22.2), and whether an
     * ACTIVE order already exists for it TODAY (same-day duplicate detection). The
     * acting user is passed so the response can say whether today's order was
     * placed by the caller or by another salesperson.
     */
    @GetMapping("/duplicate-check")
    @PreAuthorize("hasAnyRole('SALESPERSON','ADMIN','TEAM_LEAD')")
    public DuplicateCheckResponse duplicateCheck(@RequestParam("mobile") String mobile) {
        AuthPrincipal actor = currentUserService.requireCurrentUser();
        return orderService.duplicateCheck(mobile, actor);
    }

    /**
     * Active salespeople + team leads an ADMIN may place an order on behalf of
     * (the "place on behalf of" picker on the New Order form). ADMIN-only.
     */
    @GetMapping("/assignable-creators")
    @PreAuthorize("hasRole('ADMIN')")
    public List<com.shifa.oms.order.dto.AssignableCreatorResponse> assignableCreators() {
        return orderService.assignableCreators();
    }

    /**
     * Customer + shipping details from the customer's most recent order, to
     * pre-fill the New Order form when a known mobile is entered (overridable).
     */
    @GetMapping("/last-by-mobile")
    @PreAuthorize("hasAnyRole('SALESPERSON','ADMIN','TEAM_LEAD')")
    public CustomerPrefillResponse lastCustomerByMobile(@RequestParam("mobile") String mobile) {
        return orderService.lastCustomerByMobile(mobile);
    }

    /** Order detail with payment tracking fields, role-scoped (Req 21.1, 5.5). */
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('SALESPERSON','ACCOUNTANT','ADMIN','TEAM_LEAD','CA')")
    public OrderResponse detail(@PathVariable Long id) {
        AuthPrincipal actor = currentUserService.requireCurrentUser();
        return orderService.getOrder(id, actor);
    }

    /**
     * View/download the PDF invoice for an order, role-scoped like the detail
     * endpoint (Req 5.5, 21.1): a salesperson only gets their own orders, while
     * admins/accountants get any; an unknown or out-of-scope id yields 404. The
     * PDF is streamed inline as {@code application/pdf}.
     */
    @GetMapping("/{id}/invoice")
    @PreAuthorize("hasAnyRole('SALESPERSON','ACCOUNTANT','ADMIN','TEAM_LEAD','CA')")
    public ResponseEntity<byte[]> invoice(@PathVariable Long id) {
        AuthPrincipal actor = currentUserService.requireCurrentUser();
        InvoiceService.InvoiceDocument invoice = invoiceService.invoicePdf(id, actor);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\"" + invoice.filename() + "\"")
                .body(invoice.content());
    }

    /**
     * View/download the order's PRIMARY payment screenshot (Req 21.2), gated to
     * ACCOUNTANT/ADMIN.
     *
     * <p>Retained unchanged for backward compatibility now that an order may carry
     * several proofs (V65): this serves the first one. Use
     * {@link #paymentScreenshots(Long)} to enumerate them all.
     */
    @GetMapping("/{id}/payment-screenshot")
    @PreAuthorize("hasAnyRole('ACCOUNTANT','ADMIN','PAYMENT_VERIFIER','CA')")
    public ResponseEntity<Resource> paymentScreenshot(@PathVariable Long id) {
        return streamInline(orderService.getPaymentScreenshot(id));
    }

    /**
     * List every payment proof attached to an order, in upload order (V65), so the
     * order drawer and the payment-verification queue can show all of them instead
     * of just the first. Metadata only — the bytes come from
     * {@link #paymentScreenshot(Long, Long)}.
     */
    @GetMapping("/{id}/payment-screenshots")
    @PreAuthorize("hasAnyRole('ACCOUNTANT','ADMIN','PAYMENT_VERIFIER','CA')")
    public List<PaymentScreenshotResponse> paymentScreenshots(@PathVariable Long id) {
        return orderService.listPaymentScreenshots(id);
    }

    /** View/download one specific payment proof of an order by its id (V65). */
    @GetMapping("/{id}/payment-screenshots/{screenshotId}")
    @PreAuthorize("hasAnyRole('ACCOUNTANT','ADMIN','PAYMENT_VERIFIER','CA')")
    public ResponseEntity<Resource> paymentScreenshot(@PathVariable Long id,
                                                     @PathVariable Long screenshotId) {
        return streamInline(orderService.getPaymentScreenshot(id, screenshotId));
    }

    /**
     * Streams a stored object inline with its best-known MIME type and filename,
     * mirroring {@code StaffController.streamObject} so the browser renders the
     * image in place rather than downloading it.
     */
    private ResponseEntity<Resource> streamInline(StorageService.StoredObject object) {
        MediaType mediaType = object.contentType() != null
                ? MediaType.parseMediaType(object.contentType())
                : MediaType.APPLICATION_OCTET_STREAM;
        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\"" + object.filename() + "\"")
                .body(new ByteArrayResource(object.content()));
    }
}
