package com.shifa.oms.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.util.Objects;

/**
 * Running counter for voucher references, one row per (voucher type, financial year), mapped to the
 * {@code ledger_voucher_sequences} table (V54 migration).
 *
 * <p>Mirrors the single-row {@code invoice_sequence} / {@code purchase_order_sequence} pattern
 * (V15/V19) but keyed on the composite natural key {@code (voucher_type, financial_year_id)} so each
 * voucher type gets its own gap-tolerant series within a financial year (Req 5.8). A reference is
 * allocated by reading the matching row under a pessimistic write lock
 * ({@code SELECT ... FOR UPDATE}) inside the allocating transaction and incrementing
 * {@link #nextValue}; because concurrent allocations serialize on the row lock, two vouchers of the
 * same type and financial year never share a reference. Rows are created on demand by
 * {@link VoucherReferenceSequencer}.
 */
@Entity
@Table(name = "ledger_voucher_sequences")
@IdClass(LedgerVoucherSequence.Key.class)
public class LedgerVoucherSequence {

    @Id
    @Column(name = "voucher_type", nullable = false, length = 16)
    private String voucherType;

    @Id
    @Column(name = "financial_year_id", nullable = false)
    private Long financialYearId;

    @Column(name = "next_value", nullable = false)
    private long nextValue = 1L;

    protected LedgerVoucherSequence() {
        // Required by JPA.
    }

    public LedgerVoucherSequence(String voucherType, Long financialYearId, long nextValue) {
        this.voucherType = voucherType;
        this.financialYearId = financialYearId;
        this.nextValue = nextValue;
    }

    public String getVoucherType() {
        return voucherType;
    }

    public Long getFinancialYearId() {
        return financialYearId;
    }

    public long getNextValue() {
        return nextValue;
    }

    public void setNextValue(long nextValue) {
        this.nextValue = nextValue;
    }

    /** Composite primary key {@code (voucher_type, financial_year_id)}. */
    public static class Key implements Serializable {

        private String voucherType;
        private Long financialYearId;

        public Key() {
        }

        public Key(String voucherType, Long financialYearId) {
            this.voucherType = voucherType;
            this.financialYearId = financialYearId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Key key)) {
                return false;
            }
            return Objects.equals(voucherType, key.voucherType)
                    && Objects.equals(financialYearId, key.financialYearId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(voucherType, financialYearId);
        }
    }
}
