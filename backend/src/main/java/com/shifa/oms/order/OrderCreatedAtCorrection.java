package com.shifa.oms.order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/** Immutable before/after record for an ADMIN correction of an order's punched date. */
@Entity
@Table(name = "order_created_at_corrections")
public class OrderCreatedAtCorrection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "previous_created_at", nullable = false)
    private LocalDateTime previousCreatedAt;

    @Column(name = "corrected_created_at", nullable = false)
    private LocalDateTime correctedCreatedAt;

    @Column(name = "reason", nullable = false, length = 500)
    private String reason;

    @Column(name = "changed_by")
    private Long changedBy;

    @Column(name = "changed_by_username", length = 100)
    private String changedByUsername;

    @Column(name = "changed_at", insertable = false, updatable = false)
    private LocalDateTime changedAt;

    protected OrderCreatedAtCorrection() {
        // Required by JPA.
    }

    public OrderCreatedAtCorrection(Long orderId, LocalDateTime previousCreatedAt,
                                    LocalDateTime correctedCreatedAt, String reason,
                                    Long changedBy, String changedByUsername) {
        this.orderId = orderId;
        this.previousCreatedAt = previousCreatedAt;
        this.correctedCreatedAt = correctedCreatedAt;
        this.reason = reason;
        this.changedBy = changedBy;
        this.changedByUsername = changedByUsername;
    }

    public Long getId() { return id; }
    public Long getOrderId() { return orderId; }
    public LocalDateTime getPreviousCreatedAt() { return previousCreatedAt; }
    public LocalDateTime getCorrectedCreatedAt() { return correctedCreatedAt; }
    public String getReason() { return reason; }
    public Long getChangedBy() { return changedBy; }
    public String getChangedByUsername() { return changedByUsername; }
    public LocalDateTime getChangedAt() { return changedAt; }
}
