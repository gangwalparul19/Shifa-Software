package com.shifa.oms.account;

import com.shifa.oms.account.dto.WishlistItemResponse;
import com.shifa.oms.account.dto.WishlistShareResponse;
import com.shifa.oms.auth.CurrentUserService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Persisted wishlist endpoints ({@code /api/account/wishlist}). A {@code CUSTOMER}
 * manages their own save-for-later list, scoped by their user id. Add/remove are
 * idempotent (see {@link WishlistService}).
 *
 * <p>The {@code /share} sub-resource manages the customer's public, read-only
 * share link (see {@link WishlistShareService}): create-or-get a token, fetch
 * the current one, or revoke it. The token resolves to product summaries via the
 * public {@link WishlistShareController}.
 */
@RestController
@RequestMapping("/api/account/wishlist")
@PreAuthorize("hasRole('CUSTOMER')")
public class AccountWishlistController {

    private final WishlistService wishlistService;
    private final WishlistShareService wishlistShareService;
    private final CurrentUserService currentUserService;

    public AccountWishlistController(WishlistService wishlistService,
                                     WishlistShareService wishlistShareService,
                                     CurrentUserService currentUserService) {
        this.wishlistService = wishlistService;
        this.wishlistShareService = wishlistShareService;
        this.currentUserService = currentUserService;
    }

    @GetMapping
    public List<WishlistItemResponse> list() {
        return wishlistService.list(currentUserId());
    }

    @PostMapping("/{productId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void add(@PathVariable Long productId) {
        wishlistService.add(currentUserId(), productId);
    }

    @DeleteMapping("/{productId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@PathVariable Long productId) {
        wishlistService.remove(currentUserId(), productId);
    }

    /**
     * Creates or returns the customer's public share link (idempotent — one
     * active token per customer), returning the token and the host-relative
     * storefront path for the shared view.
     */
    @PostMapping("/share")
    public WishlistShareResponse createShare() {
        return WishlistShareResponse.of(wishlistShareService.createOrGetShareToken(currentUserId()));
    }

    /**
     * The customer's current share link if one exists; 204 No Content when the
     * wishlist has not been shared yet.
     */
    @GetMapping("/share")
    public ResponseEntity<WishlistShareResponse> getShare() {
        return wishlistShareService.currentToken(currentUserId())
                .map(token -> ResponseEntity.ok(WishlistShareResponse.of(token)))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /** Revokes the customer's share link; idempotent (204 whether or not one existed). */
    @DeleteMapping("/share")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeShare() {
        wishlistShareService.revokeShare(currentUserId());
    }

    private Long currentUserId() {
        return currentUserService.requireCurrentUser().userId();
    }
}
