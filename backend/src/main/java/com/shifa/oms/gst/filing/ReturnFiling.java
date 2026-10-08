package com.shifa.oms.gst.filing;

import com.shifa.oms.gst.filing.domain.FilingStatus;
import com.shifa.oms.gst.filing.domain.ReturnType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.LocalDateTime;

/**
 * The filing-status lifecycle row for a single {@code (Return_Period, Return_Type)}, mapped to the
 * {@code return_filings} table (V56). One row tracks the {@link FilingStatus} of a return type
 * (GSTR-1 or GSTR-3B) for a calendar-month period identified by {@code period_year} +
 * {@code period_month} (1–12).
 *
 * <p><strong>NOT_STARTED is implicit (Req 1.2).</strong> A missing row denotes {@code NOT_STARTED};
 * rows are created lazily on the first {@code prepare}. The service layer therefore treats the
 * absence of a row as {@link FilingStatus#NOT_STARTED} rather than persisting a row per period up
 * front.
 *
 * <p><strong>Concurrency (Req 2.8).</strong> The row carries a JPA {@link #version} optimistic-lock
 * column and is unique on {@code (period_year, period_month, return_type)}, so concurrent
 * file/re-file/reopen requests contend on the same row — exactly one commits and the others fail the
 * version check.
 *
 * <p>{@link #currentSnapshotId} points at the current {@code filing_snapshots} row and is mapped as a
 * plain {@code Long} column (not a JPA association) to avoid the circular foreign key between
 * {@code return_filings} and {@code filing_snapshots}. {@code created_at}/{@code updated_at} are
 * filled by DB defaults and therefore are not written on insert/update, following the {@code Voucher}
 * entity's convention.
 */
@Entity
@Table(name = "return_filings")
public class ReturnFiling {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "period_year", nullable = false)
    private int periodYear;

    @Column(name = "period_month", nullable = false)
    private int periodMonth;

    @Enumerated(EnumType.STRING)
    @Column(name = "return_type", nullable = false, length = 16)
    private ReturnType returnType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private FilingStatus status = FilingStatus.NOT_STARTED;

    /** Optional 1–50 char portal acknowledgement reference stored when a return is filed (Req 1.5). */
    @Column(name = "ack_reference", length = 50)
    private String ackReference;

    @Column(name = "filed_by", length = 100)
    private String filedBy;

    @Column(name = "filed_at")
    private LocalDateTime filedAt;

    @Column(name = "reopened_by", length = 100)
    private String reopenedBy;

    @Column(name = "reopened_at")
    private LocalDateTime reopenedAt;

    /**
     * Points at the current {@code filing_snapshots.id} (Req 5.1). Mapped as a plain {@code Long}
     * column rather than a JPA relationship to avoid the circular FK with {@code filing_snapshots}.
     */
    @Column(name = "current_snapshot_id")
    private Long currentSnapshotId;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;

    protected ReturnFiling() {
        // Required by JPA.
    }

    /**
     * Creates a new filing row for the given period and return type. A freshly created row starts at
     * {@link FilingStatus#NOT_STARTED}; the caller transitions it via the filing-status service.
     *
     * @param periodYear   the Indian FY year of the period
     * @param periodMonth  the calendar month (1–12) of the period
     * @param returnType   the return type (GSTR-1 or GSTR-3B)
     */
    public ReturnFiling(int periodYear, int periodMonth, ReturnType returnType) {
        this.periodYear = periodYear;
        this.periodMonth = periodMonth;
        this.returnType = returnType;
        this.status = FilingStatus.NOT_STARTED;
    }

    public Long getId() {
        return id;
    }

    public int getPeriodYear() {
        return periodYear;
    }

    public int getPeriodMonth() {
        return periodMonth;
    }

    public ReturnType getReturnType() {
        return returnType;
    }

    public FilingStatus getStatus() {
        return status;
    }

    public void setStatus(FilingStatus status) {
        this.status = status;
    }

    public String getAckReference() {
        return ackReference;
    }

    public void setAckReference(String ackReference) {
        this.ackReference = ackReference;
    }

    public String getFiledBy() {
        return filedBy;
    }

    public void setFiledBy(String filedBy) {
        this.filedBy = filedBy;
    }

    public LocalDateTime getFiledAt() {
        return filedAt;
    }

    public void setFiledAt(LocalDateTime filedAt) {
        this.filedAt = filedAt;
    }

    public String getReopenedBy() {
        return reopenedBy;
    }

    public void setReopenedBy(String reopenedBy) {
        this.reopenedBy = reopenedBy;
    }

    public LocalDateTime getReopenedAt() {
        return reopenedAt;
    }

    public void setReopenedAt(LocalDateTime reopenedAt) {
        this.reopenedAt = reopenedAt;
    }

    public Long getCurrentSnapshotId() {
        return currentSnapshotId;
    }

    public void setCurrentSnapshotId(Long currentSnapshotId) {
        this.currentSnapshotId = currentSnapshotId;
    }

    public Long getVersion() {
        return version;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
