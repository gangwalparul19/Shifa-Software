package com.shifa.oms.reconciliation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A persisted record of one committed courier COD remittance import (V99).
 *
 * <p>The authoritative COD settler is {@link RemittanceRowProcessor}; this entity
 * captures the aggregate outcome of a committed import so the weekly settlement
 * report can list what was settled, when, and with what shortfall — information
 * that was previously transient (returned only in the import response). Dry-run
 * previews never create a batch.
 */
@Entity
@Table(name = "remittance_batches")
public class RemittanceBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "original_filename", length = 255)
    private String originalFilename;

    @Column(name = "external_reference", length = 120)
    private String externalReference;

    @Column(name = "remitted_date")
    private LocalDate remittedDate;

    @Column(name = "total_rows", nullable = false)
    private int totalRows;

    @Column(name = "settled_count", nullable = false)
    private int settledCount;

    @Column(name = "partial_count", nullable = false)
    private int partialCount;

    @Column(name = "mismatch_count", nullable = false)
    private int mismatchCount;

    @Column(name = "already_settled_count", nullable = false)
    private int alreadySettledCount;

    @Column(name = "not_found_count", nullable = false)
    private int notFoundCount;

    @Column(name = "no_receivable_count", nullable = false)
    private int noReceivableCount;

    @Column(name = "error_count", nullable = false)
    private int errorCount;

    @Column(name = "total_remitted", nullable = false, precision = 14, scale = 2)
    private BigDecimal totalRemitted = BigDecimal.ZERO;

    @Column(name = "total_settled", nullable = false, precision = 14, scale = 2)
    private BigDecimal totalSettled = BigDecimal.ZERO;

    @Column(name = "total_shortfall", nullable = false, precision = 14, scale = 2)
    private BigDecimal totalShortfall = BigDecimal.ZERO;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    protected RemittanceBatch() {
        // Required by JPA.
    }

    public RemittanceBatch(String originalFilename, String externalReference, LocalDate remittedDate, Long createdBy) {
        this.originalFilename = originalFilename;
        this.externalReference = externalReference;
        this.remittedDate = remittedDate;
        this.createdBy = createdBy;
    }

    public Long getId() {
        return id;
    }

    public String getOriginalFilename() {
        return originalFilename;
    }

    public String getExternalReference() {
        return externalReference;
    }

    public LocalDate getRemittedDate() {
        return remittedDate;
    }

    public int getTotalRows() {
        return totalRows;
    }

    public void setTotalRows(int totalRows) {
        this.totalRows = totalRows;
    }

    public int getSettledCount() {
        return settledCount;
    }

    public void setSettledCount(int settledCount) {
        this.settledCount = settledCount;
    }

    public int getPartialCount() {
        return partialCount;
    }

    public void setPartialCount(int partialCount) {
        this.partialCount = partialCount;
    }

    public int getMismatchCount() {
        return mismatchCount;
    }

    public void setMismatchCount(int mismatchCount) {
        this.mismatchCount = mismatchCount;
    }

    public int getAlreadySettledCount() {
        return alreadySettledCount;
    }

    public void setAlreadySettledCount(int alreadySettledCount) {
        this.alreadySettledCount = alreadySettledCount;
    }

    public int getNotFoundCount() {
        return notFoundCount;
    }

    public void setNotFoundCount(int notFoundCount) {
        this.notFoundCount = notFoundCount;
    }

    public int getNoReceivableCount() {
        return noReceivableCount;
    }

    public void setNoReceivableCount(int noReceivableCount) {
        this.noReceivableCount = noReceivableCount;
    }

    public int getErrorCount() {
        return errorCount;
    }

    public void setErrorCount(int errorCount) {
        this.errorCount = errorCount;
    }

    public BigDecimal getTotalRemitted() {
        return totalRemitted;
    }

    public void setTotalRemitted(BigDecimal totalRemitted) {
        this.totalRemitted = totalRemitted;
    }

    public BigDecimal getTotalSettled() {
        return totalSettled;
    }

    public void setTotalSettled(BigDecimal totalSettled) {
        this.totalSettled = totalSettled;
    }

    public BigDecimal getTotalShortfall() {
        return totalShortfall;
    }

    public void setTotalShortfall(BigDecimal totalShortfall) {
        this.totalShortfall = totalShortfall;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
