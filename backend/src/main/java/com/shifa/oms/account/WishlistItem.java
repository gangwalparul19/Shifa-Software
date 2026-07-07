package com.shifa.oms.account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A persisted save-for-later entry, mapped to the pre-existing
 * {@code wishlist_items} table (V1 migration). {@code customer_id} holds the
 * registered customer's {@code users.id}; the {@code (customer_id, product_id)}
 * unique constraint keeps the wishlist idempotent (adding the same product twice
 * is a no-op).
 */
@Entity
@Table(name = "wishlist_items")
public class WishlistItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    protected WishlistItem() {
        // Required by JPA.
    }

    public WishlistItem(Long customerId, Long productId) {
        this.customerId = customerId;
        this.productId = productId;
    }

    public Long getId() {
        return id;
    }

    public Long getCustomerId() {
        return customerId;
    }

    public Long getProductId() {
        return productId;
    }
}
