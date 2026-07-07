package com.shifa.oms.procurement;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;

/**
 * A single line of a {@link PurchaseOrder}, mapped to the
 * {@code purchase_order_items} table (Feature C2): a product, the ordered
 * {@code quantity} at {@code unit_cost}, and the running {@code received_quantity}
 * updated as goods arrive.
 *
 * <p>Owned by {@link PurchaseOrder} through a unidirectional {@code @OneToMany}
 * with a {@code purchase_order_id} join column, so it carries no back-reference.
 */
@Entity
@Table(name = "purchase_order_items")
public class PurchaseOrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Column(name = "unit_cost", nullable = false, precision = 12, scale = 2)
    private BigDecimal unitCost;

    @Column(name = "received_quantity", nullable = false)
    private int receivedQuantity = 0;

    protected PurchaseOrderItem() {
        // Required by JPA.
    }

    public PurchaseOrderItem(Long productId, int quantity, BigDecimal unitCost) {
        this.productId = productId;
        this.quantity = quantity;
        this.unitCost = unitCost;
        this.receivedQuantity = 0;
    }

    /** The line total: {@code unitCost * quantity}, scaled to money. */
    public BigDecimal lineTotal() {
        return unitCost.multiply(BigDecimal.valueOf(quantity))
                .setScale(2, java.math.RoundingMode.HALF_UP);
    }

    /** The quantity still outstanding (ordered minus received), never negative. */
    public int outstandingQuantity() {
        return Math.max(0, quantity - receivedQuantity);
    }

    /** Whether this line has been fully received. */
    public boolean isFullyReceived() {
        return receivedQuantity >= quantity;
    }

    /** Adds to the received quantity (capped at the ordered quantity). */
    public void addReceived(int delta) {
        int next = this.receivedQuantity + delta;
        this.receivedQuantity = Math.min(next, quantity);
    }

    public Long getId() {
        return id;
    }

    public Long getProductId() {
        return productId;
    }

    public int getQuantity() {
        return quantity;
    }

    public BigDecimal getUnitCost() {
        return unitCost;
    }

    public int getReceivedQuantity() {
        return receivedQuantity;
    }
}
