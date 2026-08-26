package com.shifa.oms.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * A postable leaf account under an {@link AccountGroup}, mapped to the {@code ledger_accounts}
 * table (V54).
 *
 * <p>Debit and credit amounts are posted against a ledger account via voucher lines. The account's
 * <em>nature is not stored here</em> — it is derived from the owning account group at read time
 * (Req 2.2). {@link #controlKey} is the {@code ControlAccount} natural key for the seeded control
 * ledgers (e.g. {@code SALES}, {@code SUNDRY_DEBTORS}); it is unique and {@code null} for ordinary
 * user-created ledgers, letting auto-posting resolve a control role to a ledger id.
 *
 * <p>{@code created_at} / {@code updated_at} are filled by DB defaults (not written on insert/update),
 * matching the {@code AppSettings}/{@code OrderEntity} convention.
 */
@Entity
@Table(name = "ledger_accounts")
public class LedgerAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, length = 150)
    private String name;

    @Column(name = "account_group_id", nullable = false)
    private Long accountGroupId;

    /** The {@code ControlAccount} natural key for seeded control ledgers; {@code null} otherwise. */
    @Column(name = "control_key", length = 40)
    private String controlKey;

    @Column(name = "system_generated", nullable = false)
    private boolean systemGenerated = false;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;

    protected LedgerAccount() {
        // Required by JPA.
    }

    public LedgerAccount(String name, Long accountGroupId, String controlKey, boolean systemGenerated) {
        this.name = name;
        this.accountGroupId = accountGroupId;
        this.controlKey = controlKey;
        this.systemGenerated = systemGenerated;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Long getAccountGroupId() {
        return accountGroupId;
    }

    public void setAccountGroupId(Long accountGroupId) {
        this.accountGroupId = accountGroupId;
    }

    public String getControlKey() {
        return controlKey;
    }

    public void setControlKey(String controlKey) {
        this.controlKey = controlKey;
    }

    public boolean isSystemGenerated() {
        return systemGenerated;
    }

    public void setSystemGenerated(boolean systemGenerated) {
        this.systemGenerated = systemGenerated;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
