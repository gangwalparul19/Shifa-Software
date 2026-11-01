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

/**
 * A payment method offered at order entry (UPI / QR, Bank transfer, Cash, Credit Card Link, ...).
 *
 * <p>Managed by an admin under Settings. The {@link #getCode() code} is the stable key written to
 * {@code payments.payment_method}; it never changes after creation so historical payments keep
 * resolving, while the {@link #getLabel() label} is free to edit.
 */
@Entity
@Table(name = "payment_methods")
public class PaymentMethod {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code", nullable = false, unique = true, length = 30, updatable = false)
    private String code;

    @Column(name = "label", nullable = false, unique = true, length = 60)
    private String label;

    /** The salesperson must name the account that received the money. */
    @Column(name = "requires_account", nullable = false)
    private boolean requiresAccount = true;

    /**
     * When set, the receiving account is auto-selected from the active accounts of this purpose
     * (for example {@link BankAccountType#SHOPIFY_SETTLEMENT} for "Credit Card Link").
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "linked_account_type", length = 30)
    private BankAccountType linkedAccountType;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "default_method", nullable = false)
    private boolean defaultMethod;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;

    protected PaymentMethod() {
        // Required by JPA.
    }

    public PaymentMethod(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public Long getId() { return id; }
    public String getCode() { return code; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public boolean isRequiresAccount() { return requiresAccount; }
    public void setRequiresAccount(boolean requiresAccount) { this.requiresAccount = requiresAccount; }
    public BankAccountType getLinkedAccountType() { return linkedAccountType; }
    public void setLinkedAccountType(BankAccountType linkedAccountType) { this.linkedAccountType = linkedAccountType; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public boolean isDefaultMethod() { return defaultMethod; }
    public void setDefaultMethod(boolean defaultMethod) { this.defaultMethod = defaultMethod; }
    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
