package com.shifa.oms.account;

import com.shifa.oms.account.dto.WishlistItemResponse;
import com.shifa.oms.common.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;

/**
 * Public sharing of a customer's persisted wishlist. A signed-in customer
 * generates an unguessable, URL-safe token; anyone with the token can view the
 * owner's saved products (product summaries only, never customer PII) and add
 * them to their own cart. The owner can revoke/regenerate the link.
 *
 * <p>Create is idempotent: one active token exists per customer (backed by the
 * {@code customer_id} unique constraint), so re-sharing returns the same link
 * until it is revoked. Revoke is likewise idempotent — revoking when nothing is
 * shared succeeds quietly. The read-only view reuses {@link WishlistService} for
 * the product-summary projection so the shared list matches the owner's own.
 */
@Service
public class WishlistShareService {

    /** Bytes of randomness per token; 24 bytes → 32 base64url chars. */
    private static final int TOKEN_BYTES = 24;

    private final WishlistShareRepository shareRepository;
    private final WishlistService wishlistService;
    private final SecureRandom secureRandom = new SecureRandom();
    private final Base64.Encoder tokenEncoder = Base64.getUrlEncoder().withoutPadding();

    public WishlistShareService(WishlistShareRepository shareRepository,
                                WishlistService wishlistService) {
        this.shareRepository = shareRepository;
        this.wishlistService = wishlistService;
    }

    /**
     * Returns the customer's existing share token, or generates and persists a
     * new secure random one when none exists. Idempotent: one active token per
     * customer until it is revoked.
     */
    @Transactional
    public String createOrGetShareToken(Long customerId) {
        return shareRepository.findByCustomerId(customerId)
                .map(WishlistShare::getToken)
                .orElseGet(() -> shareRepository.save(
                        new WishlistShare(customerId, generateToken())).getToken());
    }

    /** The customer's current token if one exists, else empty (no side effects). */
    @Transactional(readOnly = true)
    public java.util.Optional<String> currentToken(Long customerId) {
        return shareRepository.findByCustomerId(customerId).map(WishlistShare::getToken);
    }

    /** Revokes the customer's share link; a no-op when nothing is shared (idempotent). */
    @Transactional
    public void revokeShare(Long customerId) {
        shareRepository.findByCustomerId(customerId).ifPresent(shareRepository::delete);
    }

    /**
     * The read-only product summaries for the wishlist behind a public token.
     * Reuses {@link WishlistService#list(Long)} so the shared view exposes the
     * same lightweight summaries (never customer PII). An unknown token yields a
     * 404 {@link ResourceNotFoundException}.
     */
    @Transactional(readOnly = true)
    public List<WishlistItemResponse> getSharedWishlist(String token) {
        WishlistShare share = shareRepository.findByToken(token)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Shared wishlist not found or the link has been revoked."));
        return wishlistService.list(share.getCustomerId());
    }

    /** A URL-safe, unguessable share token from {@link SecureRandom} (base64url, no padding). */
    private String generateToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return tokenEncoder.encodeToString(bytes);
    }
}
