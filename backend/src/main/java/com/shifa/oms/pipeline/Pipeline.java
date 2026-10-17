package com.shifa.oms.pipeline;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * A configurable sales {@code Pipeline} (CRM expansion Phase 3, Req 16; design
 * &sect;3.1), mapped to the {@code pipelines} table (migration V90).
 *
 * <p>Pipelines are an <em>additive configuration overlay</em>: the seeded
 * {@code Default} pipeline ({@link #isDefault} = true) reproduces the fixed
 * {@link com.shifa.oms.lead.LeadStatus} funnel 1:1, and every existing lead is
 * backfilled onto it. The {@code leads.status} column + the frozen enum
 * transition table stay authoritative for Default-pipeline leads; custom
 * pipelines are governed by their {@link PipelineStage} configuration instead.
 *
 * <p>{@code created_at} is filled by the DB default and not written on
 * insert/update (the {@code OrderEntity}/{@code LeadEntity} pattern).
 */
@Entity
@Table(name = "pipelines")
public class Pipeline {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, length = 80)
    private String name;

    @Column(name = "is_default", nullable = false)
    private boolean isDefault;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    protected Pipeline() {
        // Required by JPA.
    }

    public Pipeline(String name, boolean isDefault, int sortOrder) {
        this.name = name;
        this.isDefault = isDefault;
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

    public boolean isDefault() {
        return isDefault;
    }

    public void setDefault(boolean aDefault) {
        this.isDefault = aDefault;
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

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
