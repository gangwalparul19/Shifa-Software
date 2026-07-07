package com.shifa.oms.order;

import com.shifa.oms.auth.AuthPrincipal;
import com.shifa.oms.auth.CurrentUserService;
import com.shifa.oms.auth.Role;
import com.shifa.oms.coupon.CouponService;
import com.shifa.oms.coupon.dto.ValidateCouponRequest;
import com.shifa.oms.coupon.dto.ValidateCouponResponse;
import com.shifa.oms.order.dto.CheckoutRequest;
import com.shifa.oms.order.dto.CheckoutResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public storefront checkout endpoint (Req 3.6, 3.7).
 *
 * <p>Open to anonymous callers (see {@code SecurityConfig}: {@code /api/checkout}
 * is a public matcher, like the catalog). Bean validation enforces the required
 * fields, the 10-digit mobile (Req 3.4), and the 6-digit postal code (Req 3.5);
 * the service prices the cart from product sale prices, applies the zero-total
 * guard, and creates the order in {@code Pending_Admin_Approval}. The response
 * carries the created order id and code for the confirmation screen (Req 3.7).
 *
 * <p>Phase B: the endpoint stays public and works for guests, but when the
 * request carries a valid bearer token for a {@code CUSTOMER}, the created order
 * is stamped with that customer's user id so it appears in their order history.
 */
@RestController
@RequestMapping("/api/checkout")
public class CheckoutController {

    private final OrderService orderService;
    private final CurrentUserService currentUserService;
    private final CouponService couponService;

    public CheckoutController(OrderService orderService, CurrentUserService currentUserService,
                              CouponService couponService) {
        this.orderService = orderService;
        this.currentUserService = currentUserService;
        this.couponService = couponService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CheckoutResponse checkout(@Valid @RequestBody CheckoutRequest request) {
        // Associate the order to the logged-in customer when present; guests → null.
        Long customerUserId = currentUserService.currentUser()
                .filter(principal -> principal.role() == Role.CUSTOMER)
                .map(AuthPrincipal::userId)
                .orElse(null);
        return CheckoutResponse.from(orderService.createStorefrontOrder(request, customerUserId));
    }

    /**
     * Previews a coupon against the current cart (Phase D, public). The server
     * prices the items and evaluates the coupon so the storefront can show the
     * discount and updated total before the order is placed. Always returns 200
     * with a {@code valid} flag (an inapplicable coupon is a normal result, not
     * an error).
     */
    @PostMapping("/validate-coupon")
    public ValidateCouponResponse validateCoupon(@Valid @RequestBody ValidateCouponRequest request) {
        return couponService.validate(request);
    }
}
