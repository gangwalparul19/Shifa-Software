package com.shifa.oms.coupon;

import com.shifa.oms.audit.AuditActions;
import com.shifa.oms.audit.AuditService;
import com.shifa.oms.coupon.dto.CouponRequest;
import com.shifa.oms.coupon.dto.CouponResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Admin coupon management ({@code /api/admin/coupons}, Phase D).
 *
 * <p>Restricted to the {@code ADMIN} role via method security; unauthenticated
 * callers get 401 and non-admins 403 (standard error envelope). Supports listing,
 * creating, updating, an active toggle, and a deactivating {@code DELETE} (a
 * coupon is never hard-deleted so historical orders keep their code snapshot). A
 * duplicate code is rejected with a 409 {@code DUPLICATE_COUPON_CODE} error.
 */
@RestController
@RequestMapping("/api/admin/coupons")
@PreAuthorize("hasRole('ADMIN')")
public class AdminCouponController {

    private final CouponService couponService;
    private final AuditService auditService;

    public AdminCouponController(CouponService couponService, AuditService auditService) {
        this.couponService = couponService;
        this.auditService = auditService;
    }

    /** Lists all coupons, newest first, for the management grid. */
    @GetMapping
    public List<CouponResponse> list() {
        return couponService.list();
    }

    /** Returns a single coupon for editing. */
    @GetMapping("/{id}")
    public CouponResponse get(@PathVariable Long id) {
        return couponService.get(id);
    }

    /** Creates a coupon (code stored upper-cased; type/value sanity enforced). */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CouponResponse create(@Valid @RequestBody CouponRequest request) {
        CouponResponse response = couponService.create(request);
        auditService.record(AuditActions.COUPON_CREATED, AuditActions.ENTITY_COUPON,
                String.valueOf(response.id()), "Created coupon " + response.code());
        return response;
    }

    /** Updates a coupon. */
    @PutMapping("/{id}")
    public CouponResponse update(@PathVariable Long id, @Valid @RequestBody CouponRequest request) {
        CouponResponse response = couponService.update(id, request);
        auditService.record(AuditActions.COUPON_UPDATED, AuditActions.ENTITY_COUPON,
                String.valueOf(id), "Updated coupon " + response.code());
        return response;
    }

    /**
     * Sets a coupon's active flag ({@code ?active=true|false}). Defaults to
     * deactivating so it can double as a soft delete.
     */
    @PutMapping("/{id}/active")
    public CouponResponse setActive(@PathVariable Long id,
                                    @RequestParam(name = "active", defaultValue = "false") boolean active) {
        CouponResponse response = couponService.setActive(id, active);
        auditService.record(
                active ? AuditActions.COUPON_UPDATED : AuditActions.COUPON_DEACTIVATED,
                AuditActions.ENTITY_COUPON, String.valueOf(id),
                (active ? "Activated" : "Deactivated") + " coupon " + response.code());
        return response;
    }

    /** Deactivates a coupon (soft delete: preserves it for historical orders). */
    @DeleteMapping("/{id}")
    public CouponResponse deactivate(@PathVariable Long id) {
        CouponResponse response = couponService.setActive(id, false);
        auditService.record(AuditActions.COUPON_DEACTIVATED, AuditActions.ENTITY_COUPON,
                String.valueOf(id), "Deactivated coupon " + response.code());
        return response;
    }
}
