package com.shifa.oms.account;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Spring Data repository for {@link WishlistItem}. All operations are scoped by
 * {@code customerId} (the registered customer's user id) so a customer only ever
 * sees or mutates their own saved products.
 */
public interface WishlistItemRepository extends JpaRepository<WishlistItem, Long> {

    /** A customer's saved products, newest first. */
    List<WishlistItem> findByCustomerIdOrderByIdDesc(Long customerId);

    /** Whether a product is already on the customer's wishlist (idempotent add guard). */
    boolean existsByCustomerIdAndProductId(Long customerId, Long productId);

    /** Removes a product from the customer's wishlist; a no-op when it is not present. */
    @Transactional
    void deleteByCustomerIdAndProductId(Long customerId, Long productId);
}
