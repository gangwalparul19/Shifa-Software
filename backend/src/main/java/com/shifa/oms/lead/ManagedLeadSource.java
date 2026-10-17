package com.shifa.oms.lead;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A managed lead-source availability entry (CRM expansion Phase 3, Req 18.2),
 * mapped to the {@code lead_sources} table (migration V91), seeded from the
 * {@link com.shifa.oms.order.LeadSource} enum.
 *
 * <p>This table drives the capture/upload/report source PICKER + an
 * admin-managed availability list. The {@code LeadSource} enum stays the
 * compile-time default and {@code leads.lead_source} is unchanged — a source in
 * this table that is not an enum constant simply won't be selectable in the
 * strongly-typed capture yet (full free-form sources are a future step).
 */
@Entity
@Table(name = "lead_sources")
public class ManagedLeadSource {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, length = 40)
    private String name;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    protected ManagedLeadSource() {
        // Required by JPA.
    }

    public ManagedLeadSource(String name, boolean active, int sortOrder) {
        this.name = name;
        this.active = active;
        this.sortOrder = sortOrder;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }
}
