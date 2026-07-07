package com.shifa.oms.account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A customer's public wishlist share, mapped to the {@code wishlist_shares}
 * table (V9 migration). {@code customerId} holds the owning customer's
 * {@code users.id}; the {@code customer_id} unique constraint keeps at most one
 * active token per customer (create-or-get is idempotent), and {@code token} is
 * an unguessable URL-safe value anyone can use to view the read-only wishlist.
 */
@Entity
@Table(name = "wishlist_shares")
public class WishlistShare {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_id", nullable = false, unique = true)
    private Long customerId;

    @Column(name = "token", nullable = false, unique = true, length = 64)
    private String token;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected WishlistShare() {
        // Required by JPA.
    }

    public WishlistShare(Long customerId, String token) {
        this.customerId = customerId;
        this.token = token;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getCustomerId() {
        return customerId;
    }

    public String getToken() {
        return token;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
