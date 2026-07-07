package com.shifa.oms.inventory;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * An append-only stock ledger row, mapped to the {@code stock_movements} table
 * (V11 migration). Every change to a product's on-hand quantity — a restock, a
 * manual adjustment, a sale decrement, or a return — writes exactly one row here
 * atomically with the product update, so inventory history is fully auditable.
 *
 * <p>{@link #delta} is signed (positive for restock/return, negative for
 * sale/adjustment write-offs); {@link #balanceAfter} snapshots the product's
 * quantity immediately after the movement. {@link #createdBy} is the acting
 * user id when known (null for system/automatic movements such as sales).
 */
@Entity
@Table(name = "stock_movements")
public class StockMovement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "delta", nullable = false)
    private int delta;

    @Enumerated(EnumType.STRING)
    @Column(name = "movement_type", nullable = false, length = 20)
    private StockMovementType movementType;

    @Column(name = "reason", length = 255)
    private String reason;

    @Column(name = "balance_after", nullable = false)
    private int balanceAfter;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    protected StockMovement() {
        // Required by JPA.
    }

    public StockMovement(Long productId, int delta, StockMovementType movementType,
                         String reason, int balanceAfter, Long createdBy) {
        this.productId = productId;
        this.delta = delta;
        this.movementType = movementType;
        this.reason = reason;
        this.balanceAfter = balanceAfter;
        this.createdBy = createdBy;
    }

    public Long getId() {
        return id;
    }

    public Long getProductId() {
        return productId;
    }

    public int getDelta() {
        return delta;
    }

    public StockMovementType getMovementType() {
        return movementType;
    }

    public String getReason() {
        return reason;
    }

    public int getBalanceAfter() {
        return balanceAfter;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
