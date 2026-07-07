package com.shifa.oms.procurement;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Single-row running counter for purchase-order numbers, mapped to the
 * {@code purchase_order_sequence} table (V19 migration), mirroring the invoice
 * sequence pattern.
 *
 * <p>A PO number is allocated by reading this row under a pessimistic write lock
 * ({@code SELECT ... FOR UPDATE}) inside the allocating transaction and
 * incrementing {@link #nextValue}, so concurrent creations serialize on the row
 * lock and no PO number is ever duplicated.
 */
@Entity
@Table(name = "purchase_order_sequence")
public class PurchaseOrderSequence {

    /** The fixed id of the single counter row. */
    public static final long SINGLETON_ID = 1L;

    @Id
    @Column(name = "id", nullable = false)
    private Long id = SINGLETON_ID;

    @Column(name = "next_value", nullable = false)
    private long nextValue = 1L;

    protected PurchaseOrderSequence() {
        // Required by JPA.
    }

    public PurchaseOrderSequence(long id, long nextValue) {
        this.id = id;
        this.nextValue = nextValue;
    }

    public Long getId() {
        return id;
    }

    public long getNextValue() {
        return nextValue;
    }

    public void setNextValue(long nextValue) {
        this.nextValue = nextValue;
    }
}
