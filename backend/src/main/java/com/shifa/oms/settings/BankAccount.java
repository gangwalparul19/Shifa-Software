package com.shifa.oms.settings;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/** Configured bank/UPI destination used by payment capture and settlements. */
@Entity
@Table(name = "bank_accounts")
public class BankAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "display_name", nullable = false, unique = true, length = 100)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false, length = 30)
    private BankAccountType accountType;

    @Column(name = "bank_name", length = 120)
    private String bankName;

    @Column(name = "account_holder_name", length = 120)
    private String accountHolderName;

    @Column(name = "account_number", length = 40)
    private String accountNumber;

    @Column(name = "ifsc", length = 20)
    private String ifsc;

    @Column(name = "branch", length = 120)
    private String branch;

    @Column(name = "upi_id", length = 120)
    private String upiId;

    @Column(name = "ledger_account_id")
    private Long ledgerAccountId;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "default_account", nullable = false)
    private boolean defaultAccount;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "internal_note", length = 500)
    private String internalNote;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;

    protected BankAccount() {
    }

    public BankAccount(String displayName, BankAccountType accountType) {
        this.displayName = displayName;
        this.accountType = accountType;
    }

    public Long getId() { return id; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public BankAccountType getAccountType() { return accountType; }
    public void setAccountType(BankAccountType accountType) { this.accountType = accountType; }
    public String getBankName() { return bankName; }
    public void setBankName(String bankName) { this.bankName = bankName; }
    public String getAccountHolderName() { return accountHolderName; }
    public void setAccountHolderName(String accountHolderName) { this.accountHolderName = accountHolderName; }
    public String getAccountNumber() { return accountNumber; }
    public void setAccountNumber(String accountNumber) { this.accountNumber = accountNumber; }
    public String getIfsc() { return ifsc; }
    public void setIfsc(String ifsc) { this.ifsc = ifsc; }
    public String getBranch() { return branch; }
    public void setBranch(String branch) { this.branch = branch; }
    public String getUpiId() { return upiId; }
    public void setUpiId(String upiId) { this.upiId = upiId; }
    public Long getLedgerAccountId() { return ledgerAccountId; }
    public void setLedgerAccountId(Long ledgerAccountId) { this.ledgerAccountId = ledgerAccountId; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public boolean isDefaultAccount() { return defaultAccount; }
    public void setDefaultAccount(boolean defaultAccount) { this.defaultAccount = defaultAccount; }
    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
    public String getInternalNote() { return internalNote; }
    public void setInternalNote(String internalNote) { this.internalNote = internalNote; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    /** Last four digits for safe UI display. */
    public String maskedAccountNumber() {
        if (accountNumber == null || accountNumber.isBlank()) return null;
        String value = accountNumber.trim();
        return value.length() <= 4 ? value : "••••" + value.substring(value.length() - 4);
    }
}
