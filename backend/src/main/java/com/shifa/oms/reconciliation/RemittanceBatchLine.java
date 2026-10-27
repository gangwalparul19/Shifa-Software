package com.shifa.oms.reconciliation;

import com.shifa.oms.reconciliation.dto.RemittanceRowResult;
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

/**
 * A persisted per-line record within a committed {@link RemittanceBatch} (V99):
 * the matched order, the expected COD vs the remitted amount, the shortfall, and
 * the row outcome. Powers the weekly settlement report's per-order details and
 * shortfall highlighting.
 */
@Entity
@Table(name = "remittance_batch_lines")
public class RemittanceBatchLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "batch_id", nullable = false)
    private RemittanceBatch batch;

    @Column(name = "source_row_number", nullable = false)
    private int sourceRowNumber;

    @Column(name = "awb", length = 80)
    private String awb;

    @Column(name = "client_order_id", length = 120)
    private String clientOrderId;

    @Column(name = "customer_name", length = 160)
    private String customerName;

    @Column(name = "resolved_order_id")
    private Long resolvedOrderId;

    @Column(name = "resolved_order_code", length = 40)
    private String resolvedOrderCode;

    @Column(name = "expected_amount", precision = 12, scale = 2)
    private BigDecimal expectedAmount;

    @Column(name = "remitted_amount", precision = 12, scale = 2)
    private BigDecimal remittedAmount;

    @Column(name = "settled_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal settledAmount = BigDecimal.ZERO;

    @Column(name = "shortfall_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal shortfallAmount = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private RemittanceRowResult.Status status;

    @Column(name = "message", length = 500)
    private String message;

    @Column(name = "remitted_date")
    private LocalDate remittedDate;

    protected RemittanceBatchLine() {
        // Required by JPA.
    }

    public RemittanceBatchLine(RemittanceBatch batch, int sourceRowNumber, RemittanceRowResult.Status status) {
        this.batch = batch;
        this.sourceRowNumber = sourceRowNumber;
        this.status = status;
    }

    public Long getId() {
        return id;
    }

    public RemittanceBatch getBatch() {
        return batch;
    }

    public int getSourceRowNumber() {
        return sourceRowNumber;
    }

    public String getAwb() {
        return awb;
    }

    public void setAwb(String awb) {
        this.awb = awb;
    }

    public String getClientOrderId() {
        return clientOrderId;
    }

    public void setClientOrderId(String clientOrderId) {
        this.clientOrderId = clientOrderId;
    }

    public String getCustomerName() {
        return customerName;
    }

    public void setCustomerName(String customerName) {
        this.customerName = customerName;
    }

    public Long getResolvedOrderId() {
        return resolvedOrderId;
    }

    public void setResolvedOrderId(Long resolvedOrderId) {
        this.resolvedOrderId = resolvedOrderId;
    }

    public String getResolvedOrderCode() {
        return resolvedOrderCode;
    }

    public void setResolvedOrderCode(String resolvedOrderCode) {
        this.resolvedOrderCode = resolvedOrderCode;
    }

    public BigDecimal getExpectedAmount() {
        return expectedAmount;
    }

    public void setExpectedAmount(BigDecimal expectedAmount) {
        this.expectedAmount = expectedAmount;
    }

    public BigDecimal getRemittedAmount() {
        return remittedAmount;
    }

    public void setRemittedAmount(BigDecimal remittedAmount) {
        this.remittedAmount = remittedAmount;
    }

    public BigDecimal getSettledAmount() {
        return settledAmount;
    }

    public void setSettledAmount(BigDecimal settledAmount) {
        this.settledAmount = settledAmount;
    }

    public BigDecimal getShortfallAmount() {
        return shortfallAmount;
    }

    public void setShortfallAmount(BigDecimal shortfallAmount) {
        this.shortfallAmount = shortfallAmount;
    }

    public RemittanceRowResult.Status getStatus() {
        return status;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public LocalDate getRemittedDate() {
        return remittedDate;
    }

    public void setRemittedDate(LocalDate remittedDate) {
        this.remittedDate = remittedDate;
    }
}
