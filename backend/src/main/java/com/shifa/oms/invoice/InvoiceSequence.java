package com.shifa.oms.invoice;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Single-row running counter for invoice numbers, mapped to the
 * {@code invoice_sequence} table (V15 migration).
 *
 * <p>A number is allocated by reading this row under a pessimistic write lock
 * ({@code SELECT ... FOR UPDATE}) inside the allocating transaction and
 * incrementing {@link #nextValue}. Because concurrent allocations serialize on
 * the row lock, each caller receives a distinct value and no invoice number is
 * ever duplicated (Wave 3, Feature 1).
 */
@Entity
@Table(name = "invoice_sequence")
public class InvoiceSequence {

    /** The fixed id of the single counter row. */
    public static final long SINGLETON_ID = 1L;

    @Id
    @Column(name = "id", nullable = false)
    private Long id = SINGLETON_ID;

    @Column(name = "next_value", nullable = false)
    private long nextValue = 1L;

    protected InvoiceSequence() {
        // Required by JPA.
    }

    public InvoiceSequence(long id, long nextValue) {
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
