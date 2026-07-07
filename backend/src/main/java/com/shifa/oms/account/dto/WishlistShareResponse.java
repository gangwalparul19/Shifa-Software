package com.shifa.oms.account.dto;

/**
 * The current public share for a customer's wishlist. {@code token} is the
 * unguessable share identifier; {@code shareUrl} is the host-relative storefront
 * path for the shared view ({@code /wishlist/shared/{token}}) so the client can
 * turn it into an absolute link without the server hard-coding a host.
 */
public record WishlistShareResponse(String token, String shareUrl) {

    /** Builds the response for a token, deriving the relative storefront path. */
    public static WishlistShareResponse of(String token) {
        return new WishlistShareResponse(token, "/wishlist/shared/" + token);
    }
}
