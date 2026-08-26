package com.shifa.oms.ledger;

import com.shifa.oms.ledger.domain.AccountNature;
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
 * A node in the Chart-of-Accounts hierarchy, mapped to the {@code account_groups} table (V54).
 *
 * <p>Every account group is classified under exactly one of the five {@link AccountNature} values
 * and may nest under a parent group ({@link #parentGroupId}, self-referencing, null for a root
 * group). A child group always carries the same nature as its parent (derived by
 * {@code ChartOfAccountsService}, Req 1.3). {@link #systemGenerated} marks the seeded default groups
 * (V55 / {@code LedgerSeedService}).
 *
 * <p>Following the {@code AppSettings}/{@code OrderEntity} convention, {@code created_at} /
 * {@code updated_at} are filled by DB defaults and therefore not written on insert/update.
 */
@Entity
@Table(name = "account_groups")
public class AccountGroup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, length = 150)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "nature", nullable = false, length = 16)
    private AccountNature nature;

    /** Self-referencing parent group id; {@code null} for a root (top-level) group. */
    @Column(name = "parent_group_id")
    private Long parentGroupId;

    @Column(name = "system_generated", nullable = false)
    private boolean systemGenerated = false;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;

    protected AccountGroup() {
        // Required by JPA.
    }

    public AccountGroup(String name, AccountNature nature, Long parentGroupId, boolean systemGenerated) {
        this.name = name;
        this.nature = nature;
        this.parentGroupId = parentGroupId;
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

    public AccountNature getNature() {
        return nature;
    }

    public void setNature(AccountNature nature) {
        this.nature = nature;
    }

    public Long getParentGroupId() {
        return parentGroupId;
    }

    public void setParentGroupId(Long parentGroupId) {
        this.parentGroupId = parentGroupId;
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
