package com.shifa.oms.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;

/**
 * One Dr/Cr entry of a {@link Voucher}, mapped to the {@code voucher_lines} table (V54).
 *
 * <p>Each line references exactly one ledger account and carries either a {@link #debit} or a
 * {@link #credit} amount (never both, never neither; the strictly-positive, exactly-one-side
 * invariant is enforced by the pure {@code ledger.domain} validator before persistence, Reqs
 * 5.4–5.6). {@link #lineOrder} preserves the entry order for display; {@link #lineNarration} is an
 * optional per-line note.
 *
 * <p>Like its parent {@link Voucher}, a persisted line is immutable: fields are set once through the
 * creation constructor and expose no setters.
 */
@Entity
@Table(name = "voucher_lines")
public class VoucherLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "voucher_id", nullable = false)
    private Long voucherId;

    @Column(name = "ledger_account_id", nullable = false)
    private Long ledgerAccountId;

    @Column(name = "debit", precision = 15, scale = 2)
    private BigDecimal debit;

    @Column(name = "credit", precision = 15, scale = 2)
    private BigDecimal credit;

    @Column(name = "line_order", nullable = false)
    private int lineOrder = 0;

    @Column(name = "line_narration", length = 500)
    private String lineNarration;

    protected VoucherLine() {
        // Required by JPA.
    }

    public VoucherLine(Long voucherId,
                       Long ledgerAccountId,
                       BigDecimal debit,
                       BigDecimal credit,
                       int lineOrder,
                       String lineNarration) {
        this.voucherId = voucherId;
        this.ledgerAccountId = ledgerAccountId;
        this.debit = debit;
        this.credit = credit;
        this.lineOrder = lineOrder;
        this.lineNarration = lineNarration;
    }

    public Long getId() {
        return id;
    }

    public Long getVoucherId() {
        return voucherId;
    }

    public Long getLedgerAccountId() {
        return ledgerAccountId;
    }

    public BigDecimal getDebit() {
        return debit;
    }

    public BigDecimal getCredit() {
        return credit;
    }

    public int getLineOrder() {
        return lineOrder;
    }

    public String getLineNarration() {
        return lineNarration;
    }
}
