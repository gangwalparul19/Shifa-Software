package com.shifa.oms.procurement;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * A purchase order (PO) placed with a {@link Supplier}, mapped to the
 * {@code purchase_orders} table (Feature C2). Owns its line items through a
 * unidirectional {@code @OneToMany} with a {@code purchase_order_id} join
 * column, so the whole PO is persisted atomically.
 *
 * <p>{@code total_amount} is the sum of the line totals, computed at creation.
 * Receiving goods updates each line's {@code received_quantity}, flips the
 * status to {@link PurchaseOrderStatus#PARTIALLY_RECEIVED} /
 * {@link PurchaseOrderStatus#RECEIVED}, and stamps {@code received_at} on full
 * receipt. {@code created_by} is a best-effort actor snapshot (nullable).
 */
@Entity
@Table(name = "purchase_orders")
public class PurchaseOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "po_number", nullable = false, unique = true, length = 30)
    private String poNumber;

    @Column(name = "supplier_id", nullable = false)
    private Long supplierId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private PurchaseOrderStatus status;

    @Column(name = "notes", length = 1000)
    private String notes;

    @Column(name = "total_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalAmount = BigDecimal.ZERO;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "received_at")
    private LocalDateTime receivedAt;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @JoinColumn(name = "purchase_order_id", nullable = false)
    @OrderBy("id ASC")
    private List<PurchaseOrderItem> items = new ArrayList<>();

    protected PurchaseOrder() {
        // Required by JPA.
    }

    public PurchaseOrder(String poNumber, Long supplierId, String notes, Long createdBy) {
        this.poNumber = poNumber;
        this.supplierId = supplierId;
        this.notes = notes;
        this.createdBy = createdBy;
        this.status = PurchaseOrderStatus.ORDERED;
    }

    /** Fills the creation timestamp before the first insert so the DB never receives a null. */
    @PrePersist
    void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
    }

    /** Adds a line item to the PO. */
    public void addItem(PurchaseOrderItem item) {
        this.items.add(item);
    }

    /** Recomputes {@code total_amount} from the current line items. */
    public void recomputeTotal() {
        BigDecimal total = BigDecimal.ZERO;
        for (PurchaseOrderItem item : items) {
            total = total.add(item.lineTotal());
        }
        this.totalAmount = total.setScale(2, java.math.RoundingMode.HALF_UP);
    }

    /** Whether every line has been fully received. */
    public boolean isFullyReceived() {
        if (items.isEmpty()) {
            return false;
        }
        for (PurchaseOrderItem item : items) {
            if (!item.isFullyReceived()) {
                return false;
            }
        }
        return true;
    }

    /** Whether any line has received quantity recorded. */
    public boolean hasAnyReceipt() {
        for (PurchaseOrderItem item : items) {
            if (item.getReceivedQuantity() > 0) {
                return true;
            }
        }
        return false;
    }

    public void setStatus(PurchaseOrderStatus status) {
        this.status = status;
    }

    public void setReceivedAt(LocalDateTime receivedAt) {
        this.receivedAt = receivedAt;
    }

    public Long getId() {
        return id;
    }

    public String getPoNumber() {
        return poNumber;
    }

    public Long getSupplierId() {
        return supplierId;
    }

    public PurchaseOrderStatus getStatus() {
        return status;
    }

    public String getNotes() {
        return notes;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getReceivedAt() {
        return receivedAt;
    }

    public List<PurchaseOrderItem> getItems() {
        return items;
    }
}
