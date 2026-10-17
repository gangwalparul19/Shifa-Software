package com.shifa.oms.lead;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * A lead &harr; tag assignment, mapped to the {@code lead_tag_assignments} table
 * (CRM expansion, design &sect;1.1). One row per tag applied to a lead; the
 * {@code (lead_id, tag_id)} unique key keeps assignments idempotent (Req 4).
 *
 * <p>Modelled as a plain join row (not a bidirectional association) so tagging a
 * lead — including a terminal WON/LOST lead (Req 5.1) — never has to load or
 * mutate the {@link LeadEntity} aggregate. {@code created_at} defaults in the DB.
 */
@Entity
@Table(name = "lead_tag_assignments")
public class LeadTagAssignment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "lead_id", nullable = false)
    private Long leadId;

    @Column(name = "tag_id", nullable = false)
    private Long tagId;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    protected LeadTagAssignment() {
        // Required by JPA.
    }

    public LeadTagAssignment(Long leadId, Long tagId, Long createdBy) {
        this.leadId = leadId;
        this.tagId = tagId;
        this.createdBy = createdBy;
    }

    public Long getId() {
        return id;
    }

    public Long getLeadId() {
        return leadId;
    }

    public Long getTagId() {
        return tagId;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
