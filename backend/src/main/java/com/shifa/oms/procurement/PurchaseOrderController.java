package com.shifa.oms.procurement;

import com.shifa.oms.common.PageRequests;
import com.shifa.oms.common.PageResponse;
import com.shifa.oms.procurement.dto.CreatePurchaseOrderRequest;
import com.shifa.oms.procurement.dto.PurchaseOrderResponse;
import com.shifa.oms.procurement.dto.PurchaseOrderSummaryResponse;
import com.shifa.oms.procurement.dto.ReceivePurchaseOrderRequest;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Admin purchase-order endpoints ({@code /api/admin/purchase-orders},
 * Feature C2). ADMIN only.
 */
@RestController
@RequestMapping("/api/admin/purchase-orders")
@PreAuthorize("hasRole('ADMIN')")
public class PurchaseOrderController {

    /** Whitelist of API sort fields → JPA properties for the PO table. */
    private static final Map<String, String> SORT_WHITELIST = Map.of(
            "createdAt", "createdAt",
            "receivedAt", "receivedAt",
            "status", "status",
            "totalAmount", "totalAmount",
            "poNumber", "poNumber");

    private static final Sort DEFAULT_SORT = Sort.by(Sort.Direction.DESC, "createdAt");

    private final PurchaseOrderService purchaseOrderService;

    public PurchaseOrderController(PurchaseOrderService purchaseOrderService) {
        this.purchaseOrderService = purchaseOrderService;
    }

    /**
     * Filtered, paged, newest-first PO listing.
     *
     * @param status     exact status filter (optional)
     * @param supplierId exact supplier filter (optional)
     * @param q          case-insensitive substring over the PO number (optional)
     * @param page       zero-based page index (default 0)
     * @param size       page size (default 20, capped at 100)
     * @param sort       {@code field,dir} — createdAt/receivedAt/status/totalAmount/poNumber
     */
    @GetMapping
    public PageResponse<PurchaseOrderSummaryResponse> list(
            @RequestParam(required = false) PurchaseOrderStatus status,
            @RequestParam(required = false) Long supplierId,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort) {
        Pageable pageable = PageRequests.of(page, size, sort, SORT_WHITELIST, DEFAULT_SORT);
        return purchaseOrderService.list(status, supplierId, q, pageable);
    }

    /** A single PO with its line items. */
    @GetMapping("/{id}")
    public PurchaseOrderResponse get(@PathVariable Long id) {
        return purchaseOrderService.get(id);
    }

    /** Creates a new purchase order. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PurchaseOrderResponse create(@Valid @RequestBody CreatePurchaseOrderRequest request) {
        return purchaseOrderService.create(request);
    }

    /** Receives goods against a PO, feeding inventory and updating status. */
    @PostMapping("/{id}/receive")
    public PurchaseOrderResponse receive(@PathVariable Long id,
                                         @Valid @RequestBody ReceivePurchaseOrderRequest request) {
        return purchaseOrderService.receive(id, request);
    }

    /** Cancels a DRAFT/ORDERED purchase order. */
    @PostMapping("/{id}/cancel")
    public PurchaseOrderResponse cancel(@PathVariable Long id) {
        return purchaseOrderService.cancel(id);
    }
}
