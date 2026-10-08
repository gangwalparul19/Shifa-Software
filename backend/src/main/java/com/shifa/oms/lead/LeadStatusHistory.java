package com.shifa.oms.lead;

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
 * A persisted lead status-history record, mapped to the {@code lead_status_history}
 * table (design &sect;3.3, Req 2.6, 7.5). Exactly one row is written per accepted
 * transition — plus one synthetic creation row (with a {@code null}
 * {@code from_status}) recording the initial {@link LeadStatus#NEW} status when
 * the lead is captured.
 *
 * <p>Owned by {@link LeadEntity} through a unidirectional {@code @OneToMany} with
 * a {@code lead_id} join column, mirroring
 * {@link com.shifa.oms.order.OrderStatusHistory}. {@code changed_at} defaults in
 * the DB and is therefore not written on insert/update.
 */
@Entity
@Table(name = "lead_status_history")
public class LeadStatusHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * The owning lead's id. Read-only here ({@code insertable/updatable = false})
     * because writes are owned by {@link LeadEntity}'s {@code @JoinColumn}; this
     * mapping exists only so the detail view can query the trail by lead id.
     */
    @Column(name = "lead_id", insertable = false, updatable = false)
    private Long leadId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 16)
    private LeadStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 16)
    private LeadStatus toStatus;

    @Column(name = "actor", nullable = false, length = 100)
    private String actor;

    @Column(name = "changed_at", insertable = false, updatable = false)
    private LocalDateTime changedAt;

    protected LeadStatusHistory() {
        // Required by JPA.
    }

    public LeadStatusHistory(LeadStatus fromStatus, LeadStatus toStatus, String actor) {
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.actor = actor;
    }

    public Long getId() {
        return id;
    }

    public Long getLeadId() {
        return leadId;
    }

    public LeadStatus getFromStatus() {
        return fromStatus;
    }

    public LeadStatus getToStatus() {
        return toStatus;
    }

    public String getActor() {
        return actor;
    }

    public LocalDateTime getChangedAt() {
        return changedAt;
    }
}
