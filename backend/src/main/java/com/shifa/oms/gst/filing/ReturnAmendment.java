package com.shifa.oms.gst.filing;

import com.shifa.oms.gst.filing.domain.AmendmentStatus;
import com.shifa.oms.gst.filing.domain.AmendmentTable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * A post-filing correction detected against a Filed_Period's GSTR-1 snapshot and routed the GST way,
 * mapped to the {@code return_amendments} table (V56). GST returns &amp; filing, Reqs 3.2, 3.3, 3.4,
 * 3.6, 3.7, 3.8.
 *
 * <p><strong>Routing &amp; status.</strong> {@link #amendmentTable} (B2BA | B2CSA | CDNRA) records the
 * GSTR-1 amendment section the correction is routed into; it is {@code NULL} while the correction is
 * {@link AmendmentStatus#PENDING} (no open target period yet, Reqs 3.3, 3.6) or held for
 * {@link AmendmentStatus#MANUAL_REVIEW} (the changed section has no supported amendment table, Req 3.7).
 *
 * <p><strong>Original document identity.</strong> {@link #originalPeriodYear} +
 * {@link #originalPeriodMonth} + {@link #originalDocumentRef} (an order code / note number) identify the
 * Filed_Period entry being corrected (Req 3.4). {@link #targetPeriodYear} + {@link #targetPeriodMonth}
 * (nullable while PENDING) is the earliest non-FILED period the amendment lands in (Reqs 3.3, 3.6).
 *
 * <p><strong>Consolidation.</strong> The table is unique on
 * {@code (target_period_year, target_period_month, original_document_ref)} so repeated corrections for
 * the same document in the same target period consolidate into one row that carries the originally
 * filed {@link #originalValueJson} against the latest {@link #correctedValueJson} (Reqs 3.4, 3.8).
 * MySQL treats NULL target-period keys as distinct, so multiple PENDING rows for a document coexist
 * until a target period opens.
 *
 * <p>{@code created_at}/{@code updated_at} are filled by DB defaults and are therefore not written on
 * insert/update, following the {@link ReturnFiling} convention.
 */
@Entity
@Table(name = "return_amendments")
public class ReturnAmendment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * The GSTR-1 amendment section this correction is routed into. {@code NULL} while PENDING or held
     * for MANUAL_REVIEW (Reqs 3.2, 3.6, 3.7).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "amendment_table", length = 16)
    private AmendmentTable amendmentTable;

    @Column(name = "original_period_year", nullable = false)
    private int originalPeriodYear;

    @Column(name = "original_period_month", nullable = false)
    private int originalPeriodMonth;

    @Column(name = "original_document_ref", nullable = false, length = 100)
    private String originalDocumentRef;

    /** The target period the amendment lands in; {@code NULL} while PENDING (Reqs 3.3, 3.6). */
    @Column(name = "target_period_year")
    private Integer targetPeriodYear;

    @Column(name = "target_period_month")
    private Integer targetPeriodMonth;

    /** The originally filed value, serialised as JSON (Req 3.4). */
    @Column(name = "original_value_json", columnDefinition = "LONGTEXT")
    private String originalValueJson;

    /** The latest corrected value, serialised as JSON (Req 3.8). */
    @Column(name = "corrected_value_json", columnDefinition = "LONGTEXT")
    private String correctedValueJson;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private AmendmentStatus status = AmendmentStatus.PENDING;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;

    protected ReturnAmendment() {
        // Required by JPA.
    }

    /**
     * Creates a new amendment for a detected correction against a Filed_Period document. A freshly
     * created amendment starts {@link AmendmentStatus#PENDING} with no amendment table and no target
     * period; the service layer routes/attributes/consolidates it via the pure domain.
     *
     * @param originalPeriodYear  the Indian FY year of the Filed_Period being corrected
     * @param originalPeriodMonth the calendar month (1–12) of the Filed_Period being corrected
     * @param originalDocumentRef the corrected document reference (order code / note number)
     */
    public ReturnAmendment(int originalPeriodYear, int originalPeriodMonth, String originalDocumentRef) {
        this.originalPeriodYear = originalPeriodYear;
        this.originalPeriodMonth = originalPeriodMonth;
        this.originalDocumentRef = originalDocumentRef;
        this.status = AmendmentStatus.PENDING;
    }

    public Long getId() {
        return id;
    }

    public AmendmentTable getAmendmentTable() {
        return amendmentTable;
    }

    public void setAmendmentTable(AmendmentTable amendmentTable) {
        this.amendmentTable = amendmentTable;
    }

    public int getOriginalPeriodYear() {
        return originalPeriodYear;
    }

    public int getOriginalPeriodMonth() {
        return originalPeriodMonth;
    }

    public String getOriginalDocumentRef() {
        return originalDocumentRef;
    }

    public Integer getTargetPeriodYear() {
        return targetPeriodYear;
    }

    public void setTargetPeriodYear(Integer targetPeriodYear) {
        this.targetPeriodYear = targetPeriodYear;
    }

    public Integer getTargetPeriodMonth() {
        return targetPeriodMonth;
    }

    public void setTargetPeriodMonth(Integer targetPeriodMonth) {
        this.targetPeriodMonth = targetPeriodMonth;
    }

    public String getOriginalValueJson() {
        return originalValueJson;
    }

    public void setOriginalValueJson(String originalValueJson) {
        this.originalValueJson = originalValueJson;
    }

    public String getCorrectedValueJson() {
        return correctedValueJson;
    }

    public void setCorrectedValueJson(String correctedValueJson) {
        this.correctedValueJson = correctedValueJson;
    }

    public AmendmentStatus getStatus() {
        return status;
    }

    public void setStatus(AmendmentStatus status) {
        this.status = status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
