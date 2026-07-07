package com.shifa.oms.account;

import com.shifa.oms.account.dto.CartItemResponse;
import com.shifa.oms.account.dto.CartReplaceRequest;
import com.shifa.oms.auth.CurrentUserService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Persisted cart endpoints ({@code /api/account/cart}). A {@code CUSTOMER}
 * manages their own server-side cart, scoped by their user id, so it survives
 * across devices (ROADMAP 1.2). The cart is always saved as a whole via
 * {@code PUT} (the posted items replace the saved cart), which keeps the
 * operation idempotent (see {@link CustomerCartService}).
 */
@RestController
@RequestMapping("/api/account/cart")
@PreAuthorize("hasRole('CUSTOMER')")
public class AccountCartController {

    private final CustomerCartService cartService;
    private final CurrentUserService currentUserService;

    public AccountCartController(CustomerCartService cartService,
                                 CurrentUserService currentUserService) {
        this.cartService = cartService;
        this.currentUserService = currentUserService;
    }

    @GetMapping
    public List<CartItemResponse> list() {
        return cartService.list(currentUserId());
    }

    /**
     * Replaces the saved cart with the posted items and returns the resulting
     * cart (200 with the saved lines). Lines whose product no longer exists or
     * whose quantity is out of range are skipped, so the returned list reflects
     * exactly what was saved.
     */
    @PutMapping
    public List<CartItemResponse> replace(@Valid @RequestBody CartReplaceRequest request) {
        List<CustomerCartService.CartLine> lines = (request.items() == null)
                ? List.of()
                : request.items().stream()
                        .map(item -> new CustomerCartService.CartLine(item.productId(), item.quantity()))
                        .toList();
        return cartService.replace(currentUserId(), lines);
    }

    /**
     * Empties the signed-in customer's saved cart (204 No Content). Idempotent:
     * clearing an already-empty cart still succeeds.
     */
    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clear() {
        cartService.clearCart(currentUserId());
    }

    private Long currentUserId() {
        return currentUserService.requireCurrentUser().userId();
    }
}
