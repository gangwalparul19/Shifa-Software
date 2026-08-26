package com.shifa.oms.ledger;

import com.shifa.oms.ledger.domain.VoucherType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A posted, structurally-immutable double-entry voucher, mapped to the {@code vouchers} table (V54).
 *
 * <p><strong>Immutability (Req 6.1, 6.2).</strong> A voucher is created posted and its financial
 * fields — type, date, financial year, reference, narration, source key — expose <em>no setters</em>.
 * They are set once through the creation constructor and never mutated, so there is no code path that
 * modifies a posted voucher. The only permitted post-creation change is linking the original voucher
 * to its reversal via {@link #markReversedBy(Long)} (Req 6.4); the reverse direction
 * ({@link #reversesVoucherId}) is fixed at construction of the reversing voucher.
 *
 * <p>{@link #sourceType} / {@link #sourceId} are the optional source-document key for auto-posted
 * vouchers (unique together for at-most-once posting). {@code posted_at} / {@code created_at} /
 * {@code updated_at} are filled by DB defaults and not written on insert/update.
 */
@Entity
@Table(name = "vouchers")
public class Voucher {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "voucher_type", nullable = false, length = 16)
    private VoucherType voucherType;

    @Column(name = "voucher_date", nullable = false)
    private LocalDate voucherDate;

    @Column(name = "financial_year_id", nullable = false)
    private Long financialYearId;

    @Column(name = "voucher_reference", nullable = false, length = 40)
    private String voucherReference;

    @Column(name = "narration", length = 1000)
    private String narration;

    @Column(name = "posted", nullable = false)
    private boolean posted = true;

    @Column(name = "posted_at", insertable = false, updatable = false)
    private LocalDateTime postedAt;

    @Column(name = "posted_by", length = 100)
    private String postedBy;

    /** Source-document type for auto-posted vouchers: ORDER / PURCHASE_ORDER / EXPENSE / PAYMENT. */
    @Column(name = "source_type", length = 20)
    private String sourceType;

    @Column(name = "source_id")
    private Long sourceId;

    /** On a reversing voucher, the id of the original voucher it reverses (Req 6.4). */
    @Column(name = "reverses_voucher_id")
    private Long reversesVoucherId;

    /** On an original voucher, the id of the voucher that reversed it (Req 6.4). */
    @Column(name = "reversed_by_voucher_id")
    private Long reversedByVoucherId;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;

    protected Voucher() {
        // Required by JPA.
    }

    /**
     * Creates a posted voucher. All financial fields are fixed here and never mutated afterwards.
     *
     * @param voucherType       the voucher type (required)
     * @param voucherDate       the voucher date (required)
     * @param financialYearId   the resolved financial year id (required)
     * @param voucherReference  the per-type-per-FY unique reference (required)
     * @param narration         the voucher narration
     * @param postedBy          the acting user who posted the voucher
     * @param sourceType        the source-document type for auto-posting, or {@code null}
     * @param sourceId          the source-document id for auto-posting, or {@code null}
     * @param reversesVoucherId the id of the original voucher when this is a reversing voucher, else {@code null}
     */
    public Voucher(VoucherType voucherType,
                   LocalDate voucherDate,
                   Long financialYearId,
                   String voucherReference,
                   String narration,
                   String postedBy,
                   String sourceType,
                   Long sourceId,
                   Long reversesVoucherId) {
        this.voucherType = voucherType;
        this.voucherDate = voucherDate;
        this.financialYearId = financialYearId;
        this.voucherReference = voucherReference;
        this.narration = narration;
        this.posted = true;
        this.postedBy = postedBy;
        this.sourceType = sourceType;
        this.sourceId = sourceId;
        this.reversesVoucherId = reversesVoucherId;
    }

    /**
     * Records that this (original) voucher has been reversed by the voucher with the given id.
     * This is the only permitted post-creation mutation and exists solely to maintain the
     * bidirectional reversal link (Req 6.4); it does not alter any financial field.
     */
    public void markReversedBy(Long reversedByVoucherId) {
        this.reversedByVoucherId = reversedByVoucherId;
    }

    public Long getId() {
        return id;
    }

    public VoucherType getVoucherType() {
        return voucherType;
    }

    public LocalDate getVoucherDate() {
        return voucherDate;
    }

    public Long getFinancialYearId() {
        return financialYearId;
    }

    public String getVoucherReference() {
        return voucherReference;
    }

    public String getNarration() {
        return narration;
    }

    public boolean isPosted() {
        return posted;
    }

    public LocalDateTime getPostedAt() {
        return postedAt;
    }

    public String getPostedBy() {
        return postedBy;
    }

    public String getSourceType() {
        return sourceType;
    }

    public Long getSourceId() {
        return sourceId;
    }

    public Long getReversesVoucherId() {
        return reversesVoucherId;
    }

    public Long getReversedByVoucherId() {
        return reversedByVoucherId;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
