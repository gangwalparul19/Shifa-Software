package com.shifa.oms.account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * A persisted cart line for a signed-in customer, mapped to the
 * {@code customer_cart_items} table (V10 migration). {@code customer_id} holds
 * the registered customer's {@code users.id}; the {@code (customer_id,
 * product_id)} unique constraint keeps at most one row per product so replacing
 * the cart never produces duplicate lines.
 *
 * <p>This backs "cart survives across devices": the customer's cart is saved
 * server-side and restored after login on any device (ROADMAP 1.2). It mirrors
 * {@link WishlistItem} but additionally carries the saved {@code quantity}.
 */
@Entity
@Table(name = "customer_cart_items")
public class CustomerCartItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    /** When the row was last written; DB-maintained (default + on update), read-only here. */
    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;

    protected CustomerCartItem() {
        // Required by JPA.
    }

    public CustomerCartItem(Long customerId, Long productId, int quantity) {
        this.customerId = customerId;
        this.productId = productId;
        this.quantity = quantity;
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

    public int getQuantity() {
        return quantity;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
