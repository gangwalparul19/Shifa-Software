package com.shifa.oms.reconciliation;

import com.shifa.oms.settings.BankAccount;
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
@Table(name = "settlement_batches")
public class SettlementBatch {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Enumerated(EnumType.STRING) @Column(name = "source_channel", nullable = false, length = 30)
    private SettlementSource sourceChannel;
    @Column(name = "external_reference", length = 120) private String externalReference;
    @Column(name = "statement_date") private LocalDate statementDate;
    @ManyToOne @JoinColumn(name = "destination_account_id") private BankAccount destinationAccount;
    @Column(name = "original_filename", length = 255) private String originalFilename;
    @Column(name = "file_hash", nullable = false, unique = true, length = 64) private String fileHash;
    @Enumerated(EnumType.STRING) @Column(name = "status", nullable = false, length = 30)
    private SettlementBatchStatus status;
    @Column(name = "line_count", nullable = false) private int lineCount;
    @Column(name = "total_amount", nullable = false, precision = 14, scale = 2) private BigDecimal totalAmount = BigDecimal.ZERO;
    @Column(name = "allocated_amount", nullable = false, precision = 14, scale = 2) private BigDecimal allocatedAmount = BigDecimal.ZERO;
    @Column(name = "suspense_amount", nullable = false, precision = 14, scale = 2) private BigDecimal suspenseAmount = BigDecimal.ZERO;
    @Column(name = "note", length = 500) private String note;
    @Column(name = "created_by") private Long createdBy;
    @Column(name = "created_at", insertable = false, updatable = false) private LocalDateTime createdAt;
    @Column(name = "posted_at") private LocalDateTime postedAt;

    protected SettlementBatch() { }

    public SettlementBatch(SettlementSource sourceChannel, String externalReference, LocalDate statementDate,
                            BankAccount destinationAccount, String originalFilename, String fileHash,
                            Long createdBy) {
        this.sourceChannel = sourceChannel;
        this.externalReference = externalReference;
        this.statementDate = statementDate;
        this.destinationAccount = destinationAccount;
        this.originalFilename = originalFilename;
        this.fileHash = fileHash;
        this.status = SettlementBatchStatus.PROCESSING;
        this.createdBy = createdBy;
    }

    public Long getId() { return id; }
    public SettlementSource getSourceChannel() { return sourceChannel; }
    public String getExternalReference() { return externalReference; }
    public LocalDate getStatementDate() { return statementDate; }
    public BankAccount getDestinationAccount() { return destinationAccount; }
    public String getOriginalFilename() { return originalFilename; }
    public String getFileHash() { return fileHash; }
    public SettlementBatchStatus getStatus() { return status; }
    public void setStatus(SettlementBatchStatus status) { this.status = status; }
    public int getLineCount() { return lineCount; }
    public void setLineCount(int lineCount) { this.lineCount = lineCount; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public void setTotalAmount(BigDecimal totalAmount) { this.totalAmount = totalAmount; }
    public BigDecimal getAllocatedAmount() { return allocatedAmount; }
    public void setAllocatedAmount(BigDecimal allocatedAmount) { this.allocatedAmount = allocatedAmount; }
    public BigDecimal getSuspenseAmount() { return suspenseAmount; }
    public void setSuspenseAmount(BigDecimal suspenseAmount) { this.suspenseAmount = suspenseAmount; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public Long getCreatedBy() { return createdBy; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getPostedAt() { return postedAt; }
    public void setPostedAt(LocalDateTime postedAt) { this.postedAt = postedAt; }
}
