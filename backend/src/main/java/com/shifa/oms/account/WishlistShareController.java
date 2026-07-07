package com.shifa.oms.account;

import com.shifa.oms.account.dto.WishlistItemResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Public, read-only view of a shared wishlist ({@code /api/wishlist/shared/{token}}).
 *
 * <p>Open like the catalog and order tracking (permitted in
 * {@link com.shifa.oms.auth.SecurityConfig}): anyone with the unguessable token
 * sees the owner's saved products as lightweight product summaries — never any
 * customer PII — so they can add them to their own cart. An unknown or revoked
 * token yields 404 (see {@link WishlistShareService}).
 */
@RestController
@RequestMapping("/api/wishlist/shared")
public class WishlistShareController {

    private final WishlistShareService wishlistShareService;

    public WishlistShareController(WishlistShareService wishlistShareService) {
        this.wishlistShareService = wishlistShareService;
    }

    /** Product summaries for the wishlist behind a public share token. */
    @GetMapping("/{token}")
    public List<WishlistItemResponse> shared(@PathVariable String token) {
        return wishlistShareService.getSharedWishlist(token);
    }
}
