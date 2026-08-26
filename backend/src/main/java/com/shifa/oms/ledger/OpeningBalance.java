package com.shifa.oms.ledger;

import com.shifa.oms.ledger.domain.DrCr;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;

/**
 * The opening balance of a ledger account at the start of a financial year, mapped to the
 * {@code opening_balances} table (V54).
 *
 * <p>There is at most one opening balance per ledger account per financial year (unique
 * {@code (ledger_account_id, financial_year_id)}, Req 3.1). {@link #amount} is a positive magnitude
 * and {@link #side} records whether it is a {@code DEBIT} or {@code CREDIT} opening.
 */
@Entity
@Table(name = "opening_balances")
public class OpeningBalance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ledger_account_id", nullable = false)
    private Long ledgerAccountId;

    @Column(name = "financial_year_id", nullable = false)
    private Long financialYearId;

    @Column(name = "amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "side", nullable = false, length = 8)
    private DrCr side;

    protected OpeningBalance() {
        // Required by JPA.
    }

    public OpeningBalance(Long ledgerAccountId, Long financialYearId, BigDecimal amount, DrCr side) {
        this.ledgerAccountId = ledgerAccountId;
        this.financialYearId = financialYearId;
        this.amount = amount;
        this.side = side;
    }

    public Long getId() {
        return id;
    }

    public Long getLedgerAccountId() {
        return ledgerAccountId;
    }

    public void setLedgerAccountId(Long ledgerAccountId) {
        this.ledgerAccountId = ledgerAccountId;
    }

    public Long getFinancialYearId() {
        return financialYearId;
    }

    public void setFinancialYearId(Long financialYearId) {
        this.financialYearId = financialYearId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public DrCr getSide() {
        return side;
    }

    public void setSide(DrCr side) {
        this.side = side;
    }
}
