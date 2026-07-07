package com.shifa.oms.packing;

import com.shifa.oms.auth.AuthPrincipal;
import com.shifa.oms.auth.CurrentUserService;
import com.shifa.oms.order.dto.OrderResponse;
import com.shifa.oms.packing.dto.PackingScanRequest;
import com.shifa.oms.packing.dto.PackingScanResponse;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Packing barcode-scan endpoint (Req 11).
 *
 * <p>Restricted to {@code PACKING_USER} and {@code ADMIN} via method security:
 * unauthenticated callers get 401 and other roles 403 (Req 5.2, 5.3). A
 * successful scan returns 200 with the packed order summary (Req 11.1); an
 * unrecognized barcode returns 404 {@code BARCODE_NOT_RECOGNIZED} (Req 11.3);
 * and scanning an order that is not {@code Label_Generated} returns 409
 * {@code ORDER_NOT_PACKABLE} with the current status (Req 11.4).
 */
@RestController
@RequestMapping("/api/packing")
public class PackingController {

    private final PackingService packingService;
    private final CurrentUserService currentUserService;

    public PackingController(PackingService packingService, CurrentUserService currentUserService) {
        this.packingService = packingService;
        this.currentUserService = currentUserService;
    }

    /** Scan a packed order's barcode to mark it Packed (Req 11.1, 11.3, 11.4). */
    @PostMapping("/scan")
    @PreAuthorize("hasAnyRole('PACKING_USER','ADMIN')")
    public PackingScanResponse scan(@Valid @RequestBody PackingScanRequest request) {
        AuthPrincipal actor = currentUserService.requireCurrentUser();
        return packingService.scan(request.barcode(), actor);
    }

    /**
     * Hand a packed order over to the delivery courier
     * ({@code PACKED → HANDED_TO_DELIVERY}, Req 9.2, 9.3). Returns the updated
     * order; a non-{@code PACKED} order yields 409 {@code ORDER_NOT_HANDOVERABLE}.
     */
    @PostMapping("/{id}/handover")
    @PreAuthorize("hasAnyRole('PACKING_USER','ADMIN')")
    public OrderResponse handover(@PathVariable Long id) {
        AuthPrincipal actor = currentUserService.requireCurrentUser();
        return packingService.handover(id, actor);
    }

    /**
     * Dispatch a handed-over order by enqueuing courier assignment (Req 10.1).
     * Returns the order; a non-{@code HANDED_TO_DELIVERY} order yields 409
     * {@code ORDER_NOT_DISPATCHABLE}.
     */
    @PostMapping("/{id}/dispatch")
    @PreAuthorize("hasAnyRole('PACKING_USER','ADMIN')")
    public OrderResponse dispatch(@PathVariable Long id) {
        AuthPrincipal actor = currentUserService.requireCurrentUser();
        return packingService.dispatch(id, actor);
    }
}
