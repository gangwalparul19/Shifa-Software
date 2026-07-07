package com.shifa.oms.returns;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A return / refund / RTO record against an order, mapped to the
 * {@code order_returns} table ("operations depth" Feature 1).
 *
 * <p>Owns its own lifecycle {@link ReturnStatus} (REQUESTED &rarr; APPROVED
 * &rarr; REFUNDED, or REJECTED). {@code restocked} records whether the order's
 * line items were returned to inventory at approval time; {@code refundAmount}
 * is nullable until known. {@code createdBy} is a best-effort actor snapshot
 * (nullable, matching the audit convention). {@code created_at} is filled before
 * the first insert; {@code updated_at} is stamped on each state change.
 */
@Entity
@Table(name = "order_returns")
public class OrderReturn {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "reason", nullable = false, length = 250)
    private String reason;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ReturnStatus status;

    @Column(name = "refund_amount", precision = 12, scale = 2)
    private BigDecimal refundAmount;

    @Column(name = "restocked", nullable = false)
    private boolean restocked = false;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    protected OrderReturn() {
        // Required by JPA.
    }

    public OrderReturn(Long orderId, String reason, String notes, Long createdBy) {
        this.orderId = orderId;
        this.reason = reason;
        this.notes = notes;
        this.createdBy = createdBy;
        this.status = ReturnStatus.INITIAL;
    }

    /** Fills the creation timestamp before the first insert so the DB never receives a null. */
    @PrePersist
    void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
    }

    /** Moves this return to a new status and stamps {@code updated_at}. */
    public void changeStatus(ReturnStatus newStatus) {
        this.status = newStatus;
        this.updatedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public Long getOrderId() {
        return orderId;
    }

    public String getReason() {
        return reason;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public ReturnStatus getStatus() {
        return status;
    }

    public BigDecimal getRefundAmount() {
        return refundAmount;
    }

    public void setRefundAmount(BigDecimal refundAmount) {
        this.refundAmount = refundAmount;
    }

    public boolean isRestocked() {
        return restocked;
    }

    public void setRestocked(boolean restocked) {
        this.restocked = restocked;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
