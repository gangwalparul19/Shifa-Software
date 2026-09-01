package com.shifa.oms.gst.filing;

import com.shifa.oms.gst.filing.domain.ReturnType;
import com.shifa.oms.gst.filing.domain.SnapshotVersioning;
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
 * An immutable, append-only copy of the exact figures filed for a
 * {@link com.shifa.oms.gst.filing.domain.ReturnPeriod}/{@link ReturnType}, mapped to the
 * {@code filing_snapshots} table (V56). GST returns &amp; filing, Reqs 5.1, 5.2, 5.3, 5.5, 5.6, 5.7.
 *
 * <p><strong>Immutability (Req 5.5).</strong> A snapshot is a permanent audit record of what was
 * filed. Every field is set once through the creation constructor and exposes <em>no setter</em> —
 * in particular there is no mutator for {@link #payloadJson} or {@link #version} — and the service
 * layer exposes no update or delete path. Reopening a period and re-filing appends a <em>new</em>
 * snapshot with the next {@link #version} rather than modifying an existing one, so the originally
 * filed figures remain intact and auditable (Reqs 5.5, 5.6).
 *
 * <p>{@link #payloadJson} is the exact figures serialised as JSON: for GSTR-1 the full portal
 * sections (b2b, b2cl, b2cs, cdnr, cdnur, hsn, docs) as assembled at filing time (Req 5.2); for
 * GSTR-3B the section-mapped output tax, ITC, and net payable as computed at filing time (Req 5.3).
 * It is served back verbatim with no recomputation (Reqs 2.2, 5.4).
 *
 * <p>{@link #version} is monotonic from {@code 1} per filing and the highest version is the current
 * snapshot (Req 5.6); the entity implements {@link SnapshotVersioning.Versioned} so
 * {@link SnapshotVersioning#nextVersion} / {@link SnapshotVersioning#current} apply to it directly.
 * {@code created_at} is filled by a DB default and not written on insert.
 */
@Entity
@Table(name = "filing_snapshots")
public class FilingSnapshot implements SnapshotVersioning.Versioned {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "return_filing_id", nullable = false)
    private Long returnFilingId;

    @Enumerated(EnumType.STRING)
    @Column(name = "return_type", nullable = false, length = 16)
    private ReturnType returnType;

    @Column(name = "period_year", nullable = false)
    private int periodYear;

    @Column(name = "period_month", nullable = false)
    private int periodMonth;

    @Column(name = "version", nullable = false)
    private int version;

    @Column(name = "payload_json", columnDefinition = "LONGTEXT", nullable = false)
    private String payloadJson;

    @Column(name = "filed_by", length = 100)
    private String filedBy;

    @Column(name = "filed_at")
    private LocalDateTime filedAt;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    protected FilingSnapshot() {
        // Required by JPA.
    }

    /**
     * Creates an immutable filing snapshot. All fields are fixed here and never mutated afterwards.
     *
     * @param returnFilingId the owning {@link ReturnFiling} id (required)
     * @param returnType     the return type this snapshot captures (required)
     * @param periodYear     the return period's Indian FY year
     * @param periodMonth    the return period's month (1–12)
     * @param version        the monotonic snapshot version (from {@code 1}, Req 5.6)
     * @param payloadJson    the exact filed figures serialised as JSON (Reqs 5.2, 5.3)
     * @param filedBy        the acting user who filed
     * @param filedAt        the filing timestamp (Asia/Kolkata, to the second — Req 5.1)
     */
    public FilingSnapshot(Long returnFilingId,
                          ReturnType returnType,
                          int periodYear,
                          int periodMonth,
                          int version,
                          String payloadJson,
                          String filedBy,
                          LocalDateTime filedAt) {
        this.returnFilingId = returnFilingId;
        this.returnType = returnType;
        this.periodYear = periodYear;
        this.periodMonth = periodMonth;
        this.version = version;
        this.payloadJson = payloadJson;
        this.filedBy = filedBy;
        this.filedAt = filedAt;
    }

    public Long getId() {
        return id;
    }

    public Long getReturnFilingId() {
        return returnFilingId;
    }

    public ReturnType getReturnType() {
        return returnType;
    }

    public int getPeriodYear() {
        return periodYear;
    }

    public int getPeriodMonth() {
        return periodMonth;
    }

    public int getVersion() {
        return version;
    }

    /**
     * @return this snapshot's version number (monotonic from {@code 1}) — the
     *         {@link SnapshotVersioning.Versioned} contract, so {@code SnapshotVersioning.current(...)}
     *         works over persisted snapshots (Reqs 5.1, 5.6).
     */
    @Override
    public int version() {
        return version;
    }

    public String getPayloadJson() {
        return payloadJson;
    }

    public String getFiledBy() {
        return filedBy;
    }

    public LocalDateTime getFiledAt() {
        return filedAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
