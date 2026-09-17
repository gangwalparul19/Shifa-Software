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

    /**
     * The CASH refundable/refunded to the customer — money out. For an RTO this is
     * only what was actually collected (nothing at all for a pure COD order), not
     * the order value. Feeds the money reports / P&amp;L "refunds".
     */
    @Column(name = "refund_amount", precision = 12, scale = 2)
    private BigDecimal refundAmount;

    /**
     * The GST-inclusive <strong>value of supply reversed</strong> by the credit
     * note issued for this return (V64). Distinct from {@link #refundAmount}:
     * under GST a returned/RTO'd consignment reverses the <em>whole</em> supply,
     * so the credit note carries the full invoice value plus its GST even when
     * little or no cash was ever collected. Feeds the GSTR-1 CDNR/CDNUR sections.
     *
     * <p>{@code null} on legacy rows created before this split; the GSTR-1 builder
     * then falls back to {@link #refundAmount} so already-filed periods keep
     * reporting exactly what they reported before.
     */
    @Column(name = "credit_note_value", precision = 12, scale = 2)
    private BigDecimal creditNoteValue;

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

    /** The GST-inclusive value of supply reversed by the credit note, or {@code null} (V64). */
    public BigDecimal getCreditNoteValue() {
        return creditNoteValue;
    }

    /**
     * Records the GST-inclusive value of supply reversed by this return's credit
     * note — the full invoice value for a whole-consignment return/RTO, which is
     * independent of how much cash was collected.
     */
    public void setCreditNoteValue(BigDecimal creditNoteValue) {
        this.creditNoteValue = creditNoteValue;
    }

    /**
     * The value to report on the GSTR-1 credit note: {@link #getCreditNoteValue()}
     * when recorded, else {@link #getRefundAmount()} for legacy rows written before
     * the two were separated (V64), so previously-filed periods are unchanged.
     */
    public BigDecimal creditNoteValueOrRefund() {
        return creditNoteValue != null ? creditNoteValue : refundAmount;
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
