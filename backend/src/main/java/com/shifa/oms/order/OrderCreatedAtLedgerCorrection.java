package com.shifa.oms.order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/** Durable link between a punched-date correction and its accounting reversal/replacement. */
@Entity
@Table(name = "order_created_at_ledger_corrections")
public class OrderCreatedAtLedgerCorrection {

    public static final String STATUS_STARTED = "STARTED";
    public static final String STATUS_REVERSED = "REVERSED";
    public static final String STATUS_COMPLETED = "COMPLETED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_created_at_correction_id", nullable = false)
    private Long orderCreatedAtCorrectionId;

    @Column(name = "source_type", nullable = false, length = 30)
    private String sourceType;

    @Column(name = "source_id", nullable = false)
    private Long sourceId;

    @Column(name = "original_voucher_id", nullable = false)
    private Long originalVoucherId;

    @Column(name = "reversal_voucher_id")
    private Long reversalVoucherId;

    @Column(name = "replacement_voucher_id")
    private Long replacementVoucherId;

    @Column(name = "status", nullable = false, length = 20)
    private String status = STATUS_STARTED;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    protected OrderCreatedAtLedgerCorrection() {
        // Required by JPA.
    }

    public OrderCreatedAtLedgerCorrection(Long orderCreatedAtCorrectionId, String sourceType,
                                          Long sourceId, Long originalVoucherId) {
        this.orderCreatedAtCorrectionId = orderCreatedAtCorrectionId;
        this.sourceType = sourceType;
        this.sourceId = sourceId;
        this.originalVoucherId = originalVoucherId;
    }

    public void markReversed(Long voucherId) {
        this.reversalVoucherId = voucherId;
        this.status = STATUS_REVERSED;
    }

    public void markCompleted(Long voucherId) {
        this.replacementVoucherId = voucherId;
        this.status = STATUS_COMPLETED;
        this.completedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public Long getOrderCreatedAtCorrectionId() { return orderCreatedAtCorrectionId; }
    public String getSourceType() { return sourceType; }
    public Long getSourceId() { return sourceId; }
    public Long getOriginalVoucherId() { return originalVoucherId; }
    public Long getReversalVoucherId() { return reversalVoucherId; }
    public Long getReplacementVoucherId() { return replacementVoucherId; }
    public String getStatus() { return status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getCompletedAt() { return completedAt; }
}
