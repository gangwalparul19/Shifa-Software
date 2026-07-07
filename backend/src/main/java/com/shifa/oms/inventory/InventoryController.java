package com.shifa.oms.inventory;

import com.shifa.oms.audit.AuditActions;
import com.shifa.oms.audit.AuditService;
import com.shifa.oms.auth.AuthPrincipal;
import com.shifa.oms.auth.CurrentUserService;
import com.shifa.oms.common.ValidationException;
import com.shifa.oms.inventory.dto.AdjustRequest;
import com.shifa.oms.inventory.dto.InventoryProductResponse;
import com.shifa.oms.inventory.dto.RestockRequest;
import com.shifa.oms.inventory.dto.StockMovementResponse;
import com.shifa.oms.product.Product;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Admin inventory / stock-management endpoints (Feature 1).
 *
 * <p>Restricted to the {@code ADMIN} role via method security, matching
 * {@code AdminProductController} / {@code SettingsController}; unauthenticated
 * callers get 401 and non-admins 403.
 */
@RestController
@RequestMapping("/api/admin/inventory")
@PreAuthorize("hasRole('ADMIN')")
public class InventoryController {

    private final StockService stockService;
    private final CurrentUserService currentUserService;
    private final AuditService auditService;

    public InventoryController(StockService stockService,
                               CurrentUserService currentUserService,
                               AuditService auditService) {
        this.stockService = stockService;
        this.currentUserService = currentUserService;
        this.auditService = auditService;
    }

    /** Lists all products with their stock info (id, sku, name, tracking, qty, threshold, status). */
    @GetMapping
    public List<InventoryProductResponse> list() {
        return stockService.inventory().stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * Lists tracked products that are low on stock. When {@code includeOutOfStock}
     * is true, out-of-stock tracked products are included too.
     */
    @GetMapping("/low-stock")
    public List<InventoryProductResponse> lowStock(
            @RequestParam(name = "includeOutOfStock", defaultValue = "false") boolean includeOutOfStock) {
        return stockService.lowStock(includeOutOfStock).stream()
                .map(this::toResponse)
                .toList();
    }

    /** Adds stock to a product (RESTOCK), returning the updated stock info. */
    @PostMapping("/{productId}/restock")
    public InventoryProductResponse restock(@PathVariable Long productId,
                                            @Valid @RequestBody RestockRequest request) {
        stockService.restock(productId, request.quantity(), request.reason(), currentUserId());
        auditService.record(AuditActions.STOCK_RESTOCKED, AuditActions.ENTITY_PRODUCT,
                String.valueOf(productId),
                "Restocked +" + request.quantity() + reasonSuffix(request.reason()));
        return toResponse(stockService.product(productId));
    }

    /** Applies a signed stock adjustment to a product, returning the updated stock info. */
    @PostMapping("/{productId}/adjust")
    public InventoryProductResponse adjust(@PathVariable Long productId,
                                           @Valid @RequestBody AdjustRequest request) {
        if (request.delta() == 0) {
            throw new ValidationException("Adjustment delta must not be zero.");
        }
        stockService.adjust(productId, request.delta(), StockMovementType.ADJUSTMENT,
                request.reason(), currentUserId());
        auditService.record(AuditActions.STOCK_ADJUSTED, AuditActions.ENTITY_PRODUCT,
                String.valueOf(productId),
                "Adjusted stock by " + request.delta() + reasonSuffix(request.reason()));
        return toResponse(stockService.product(productId));
    }

    private static String reasonSuffix(String reason) {
        return (reason == null || reason.isBlank()) ? "" : " (" + reason.trim() + ")";
    }

    /** Returns the recent stock movements for a product, newest first. */
    @GetMapping("/{productId}/movements")
    public List<StockMovementResponse> movements(@PathVariable Long productId) {
        return stockService.movements(productId).stream()
                .map(StockMovementResponse::from)
                .toList();
    }

    private InventoryProductResponse toResponse(Product product) {
        return InventoryProductResponse.from(product, stockService.thresholdFor(product));
    }

    private Long currentUserId() {
        AuthPrincipal principal = currentUserService.requireCurrentUser();
        return principal.userId();
    }
}
