package com.shifa.oms.order;

import com.shifa.oms.auth.AuthPrincipal;
import com.shifa.oms.auth.CurrentUserService;
import com.shifa.oms.auth.Role;
import com.shifa.oms.common.ValidationException;
import com.shifa.oms.invoice.InvoiceService;
import com.shifa.oms.order.dto.CreateOrderRequest;
import com.shifa.oms.order.dto.CustomerPrefillResponse;
import com.shifa.oms.order.dto.DuplicateCheckResponse;
import com.shifa.oms.order.dto.OrderResponse;
import com.shifa.oms.order.dto.OrderSummaryResponse;
import com.shifa.oms.order.dto.ScreenshotUploadResponse;
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

    public OrderController(OrderService orderService, CurrentUserService currentUserService,
                           InvoiceService invoiceService, ProductService productService,
                           OrderSuggestionService suggestionService) {
        this.orderService = orderService;
        this.currentUserService = currentUserService;
        this.invoiceService = invoiceService;
        this.productService = productService;
        this.suggestionService = suggestionService;
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

    /** Whether prior orders exist for a mobile number (Req 22.2). */
    @GetMapping("/duplicate-check")
    @PreAuthorize("hasAnyRole('SALESPERSON','ADMIN','TEAM_LEAD')")
    public DuplicateCheckResponse duplicateCheck(@RequestParam("mobile") String mobile) {
        return orderService.duplicateCheck(mobile);
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

    /** View/download the payment screenshot for an order (Req 21.2), gated to ACCOUNTANT/ADMIN. */
    @GetMapping("/{id}/payment-screenshot")
    @PreAuthorize("hasAnyRole('ACCOUNTANT','ADMIN','PAYMENT_VERIFIER','CA')")
    public ResponseEntity<Resource> paymentScreenshot(@PathVariable Long id) {
        StorageService.StoredObject object = orderService.getPaymentScreenshot(id);
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
