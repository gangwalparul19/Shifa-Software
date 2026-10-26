package com.shifa.oms.reconciliation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "settlement_allocations")
public class SettlementAllocation {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(optional = false) @JoinColumn(name = "settlement_line_id", nullable = false) private SettlementLine settlementLine;
    @Enumerated(EnumType.STRING) @Column(name = "target_type", nullable = false, length = 30) private SettlementTargetType targetType;
    @Column(name = "order_id") private Long orderId;
    @Column(name = "payment_id") private Long paymentId;
    @Column(name = "receivable_id") private Long receivableId;
    @Column(name = "amount", nullable = false, precision = 14, scale = 2) private BigDecimal amount;
    @Column(name = "allocated_at", insertable = false, updatable = false) private LocalDateTime allocatedAt;
    @Column(name = "allocated_by") private Long allocatedBy;
    protected SettlementAllocation() { }
    public SettlementAllocation(SettlementLine line, SettlementTargetType targetType, Long orderId, Long paymentId,
                                Long receivableId, BigDecimal amount, Long allocatedBy) {
        this.settlementLine = line; this.targetType = targetType; this.orderId = orderId; this.paymentId = paymentId;
        this.receivableId = receivableId; this.amount = amount; this.allocatedBy = allocatedBy;
    }
    public Long getId() { return id; }
    public SettlementLine getSettlementLine() { return settlementLine; }
    public SettlementTargetType getTargetType() { return targetType; }
    public Long getOrderId() { return orderId; }
    public Long getPaymentId() { return paymentId; }
    public Long getReceivableId() { return receivableId; }
    public BigDecimal getAmount() { return amount; }
    public LocalDateTime getAllocatedAt() { return allocatedAt; }
    public Long getAllocatedBy() { return allocatedBy; }
}
