package com.shifa.oms.account;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Spring Data repository for {@link WishlistShare}. A customer has at most one
 * share row (looked up by {@code customerId} for create-or-get / revoke); the
 * public shared view resolves a wishlist by its {@code token}.
 */
public interface WishlistShareRepository extends JpaRepository<WishlistShare, Long> {

    /** The customer's active share, if one exists. */
    Optional<WishlistShare> findByCustomerId(Long customerId);

    /** The share for a public token, if the token is known. */
    Optional<WishlistShare> findByToken(String token);
}
