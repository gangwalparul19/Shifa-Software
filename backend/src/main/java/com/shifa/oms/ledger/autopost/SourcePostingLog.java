package com.shifa.oms.ledger.autopost;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * A per-source-document idempotency and trace record for decoupled auto-posting, mapped to the
 * {@code ledger_source_postings} table (V54).
 *
 * <p>One row is written per successfully auto-posted source document ({@link #sourceType},
 * {@link #sourceId} → {@link #voucherId}), giving a fast idempotency pre-check and a CA-visible trace
 * from a source document to its voucher (Req 18.3). The authoritative at-most-once guard remains the
 * unique {@code (source_type, source_id)} constraint on the {@code vouchers} table; this row mirrors
 * it (also unique on {@code (source_type, source_id)}).
 *
 * <p>{@code created_at} is filled by a DB default and not written on insert.
 */
@Entity
@Table(name = "ledger_source_postings")
public class SourcePostingLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "source_type", nullable = false, length = 20)
    private String sourceType;

    @Column(name = "source_id", nullable = false)
    private Long sourceId;

    @Column(name = "voucher_id", nullable = false)
    private Long voucherId;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    protected SourcePostingLog() {
        // Required by JPA.
    }

    public SourcePostingLog(String sourceType, Long sourceId, Long voucherId) {
        this.sourceType = sourceType;
        this.sourceId = sourceId;
        this.voucherId = voucherId;
    }

    public Long getId() {
        return id;
    }

    public String getSourceType() {
        return sourceType;
    }

    public Long getSourceId() {
        return sourceId;
    }

    public Long getVoucherId() {
        return voucherId;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
