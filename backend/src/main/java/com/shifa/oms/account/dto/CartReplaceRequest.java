package com.shifa.oms.account.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * Payload for {@code PUT /api/account/cart}: the full desired cart to save for
 * the signed-in customer. The posted lines replace whatever cart was saved
 * before (see {@code CustomerCartService.replace}).
 *
 * <p>Validation here is intentionally lenient: the service skips lines whose
 * product no longer exists or whose quantity is out of range, so a stale client
 * cart can still be saved (minus the bad lines) rather than being rejected
 * wholesale. Only the structural {@code productId} presence is asserted.
 *
 * @param items the desired cart lines (may be empty to clear the cart)
 */
public record CartReplaceRequest(List<CartLineRequest> items) {

    /** A single desired cart line: a product id and a quantity. */
    public record CartLineRequest(
            @NotNull(message = "productId is required")
            Long productId,
            int quantity) {
    }
}
