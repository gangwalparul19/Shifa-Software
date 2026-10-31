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
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "settlement_lines")
public class SettlementLine {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(optional = false) @JoinColumn(name = "batch_id", nullable = false) private SettlementBatch batch;
    @Column(name = "source_row_number", nullable = false) private int sourceRowNumber;
    @Column(name = "order_code", length = 40) private String orderCode;
    @Column(name = "shopify_order_id", length = 64) private String shopifyOrderId;
    @Column(name = "awb", length = 80) private String awb;
    @Column(name = "utr", length = 80) private String utr;
    @Column(name = "transaction_date") private LocalDate transactionDate;
    @Column(name = "amount", nullable = false, precision = 14, scale = 2) private BigDecimal amount = BigDecimal.ZERO;
    @Column(name = "expected_amount", precision = 14, scale = 2) private BigDecimal expectedAmount;
    @Column(name = "variance", nullable = false, precision = 14, scale = 2) private BigDecimal variance = BigDecimal.ZERO;
    @Column(name = "allocated_amount", nullable = false, precision = 14, scale = 2) private BigDecimal allocatedAmount = BigDecimal.ZERO;
    @Column(name = "suspense_amount", nullable = false, precision = 14, scale = 2) private BigDecimal suspenseAmount = BigDecimal.ZERO;
    @Enumerated(EnumType.STRING) @Column(name = "match_status", nullable = false, length = 30) private SettlementMatchStatus matchStatus;
    @Enumerated(EnumType.STRING) @Column(name = "match_method", length = 40) private SettlementMatchMethod matchMethod;
    @Column(name = "resolved_order_id") private Long resolvedOrderId;
    @Column(name = "resolved_payment_id") private Long resolvedPaymentId;
    @Column(name = "resolved_receivable_id") private Long resolvedReceivableId;
    @Column(name = "message", length = 500) private String message;
    @Column(name = "resolved_by") private Long resolvedBy;
    @Column(name = "resolved_at") private LocalDateTime resolvedAt;
    @Column(name = "date_defaulted", nullable = false) private boolean dateDefaulted;

    protected SettlementLine() { }
    public SettlementLine(SettlementBatch batch, int sourceRowNumber) { this.batch = batch; this.sourceRowNumber = sourceRowNumber; }
    public Long getId() { return id; }
    public SettlementBatch getBatch() { return batch; }
    public int getSourceRowNumber() { return sourceRowNumber; }
    public String getOrderCode() { return orderCode; }
    public void setOrderCode(String v) { orderCode = v; }
    public String getShopifyOrderId() { return shopifyOrderId; }
    public void setShopifyOrderId(String v) { shopifyOrderId = v; }
    public String getAwb() { return awb; }
    public void setAwb(String v) { awb = v; }
    public String getUtr() { return utr; }
    public void setUtr(String v) { utr = v; }
    public LocalDate getTransactionDate() { return transactionDate; }
    public void setTransactionDate(LocalDate v) { transactionDate = v; }
    public boolean isDateDefaulted() { return dateDefaulted; }
    public void setDateDefaulted(boolean v) { dateDefaulted = v; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal v) { amount = v; }
    public BigDecimal getExpectedAmount() { return expectedAmount; }
    public void setExpectedAmount(BigDecimal v) { expectedAmount = v; }
    public BigDecimal getVariance() { return variance; }
    public void setVariance(BigDecimal v) { variance = v; }
    public BigDecimal getAllocatedAmount() { return allocatedAmount; }
    public void setAllocatedAmount(BigDecimal v) { allocatedAmount = v; }
    public BigDecimal getSuspenseAmount() { return suspenseAmount; }
    public void setSuspenseAmount(BigDecimal v) { suspenseAmount = v; }
    public SettlementMatchStatus getMatchStatus() { return matchStatus; }
    public void setMatchStatus(SettlementMatchStatus v) { matchStatus = v; }
    public SettlementMatchMethod getMatchMethod() { return matchMethod; }
    public void setMatchMethod(SettlementMatchMethod v) { matchMethod = v; }
    public Long getResolvedOrderId() { return resolvedOrderId; }
    public void setResolvedOrderId(Long v) { resolvedOrderId = v; }
    public Long getResolvedPaymentId() { return resolvedPaymentId; }
    public void setResolvedPaymentId(Long v) { resolvedPaymentId = v; }
    public Long getResolvedReceivableId() { return resolvedReceivableId; }
    public void setResolvedReceivableId(Long v) { resolvedReceivableId = v; }
    public String getMessage() { return message; }
    public void setMessage(String v) { message = v; }
    public Long getResolvedBy() { return resolvedBy; }
    public void setResolvedBy(Long v) { resolvedBy = v; }
    public LocalDateTime getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(LocalDateTime v) { resolvedAt = v; }
}
