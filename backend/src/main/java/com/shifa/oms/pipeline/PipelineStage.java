package com.shifa.oms.pipeline;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A configurable {@code Pipeline_Stage} within a {@link Pipeline} (CRM expansion
 * Phase 3, Req 17; design &sect;3.1), mapped to the {@code pipeline_stages}
 * table (migration V90).
 *
 * <ul>
 *   <li>{@link #isEntry} — the single stage a new lead enters the pipeline at.</li>
 *   <li>{@link #terminalKind} — {@code null} for an active stage, else
 *       {@code WON}/{@code LOST}/{@code CLOSED} for a terminal stage that ends
 *       the funnel.</li>
 *   <li>{@link #legacyStatus} — for the Default pipeline only, the
 *       {@link com.shifa.oms.lead.LeadStatus} enum name this stage maps to, so
 *       the {@code leads.status} column can stay authoritative and the V90
 *       backfill can resolve a stage from a status.</li>
 *   <li>{@link #active} — a retired stage ({@code active=false}) stays valid for
 *       leads/history already in it (retire &ne; delete, Req 17.3) but is not a
 *       selectable transition target.</li>
 * </ul>
 */
@Entity
@Table(name = "pipeline_stages")
public class PipelineStage {

    /** Terminal-kind discriminator values for {@link #terminalKind}. */
    public static final String TERMINAL_WON = "WON";
    public static final String TERMINAL_LOST = "LOST";
    public static final String TERMINAL_CLOSED = "CLOSED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pipeline_id", nullable = false)
    private Long pipelineId;

    @Column(name = "name", nullable = false, length = 60)
    private String name;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "is_entry", nullable = false)
    private boolean isEntry;

    @Column(name = "terminal_kind", length = 10)
    private String terminalKind;

    @Column(name = "legacy_status", length = 16)
    private String legacyStatus;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    protected PipelineStage() {
        // Required by JPA.
    }

    public PipelineStage(Long pipelineId, String name, int sortOrder, boolean isEntry,
                         String terminalKind, String legacyStatus) {
        this.pipelineId = pipelineId;
        this.name = name;
        this.sortOrder = sortOrder;
        this.isEntry = isEntry;
        this.terminalKind = terminalKind;
        this.legacyStatus = legacyStatus;
    }

    /** Whether this stage ends the funnel (has a terminal kind). */
    public boolean isTerminal() {
        return terminalKind != null && !terminalKind.isBlank();
    }

    public Long getId() {
        return id;
    }

    public Long getPipelineId() {
        return pipelineId;
    }

    public void setPipelineId(Long pipelineId) {
        this.pipelineId = pipelineId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }

    public boolean isEntry() {
        return isEntry;
    }

    public void setEntry(boolean entry) {
        this.isEntry = entry;
    }

    public String getTerminalKind() {
        return terminalKind;
    }

    public void setTerminalKind(String terminalKind) {
        this.terminalKind = terminalKind;
    }

    public String getLegacyStatus() {
        return legacyStatus;
    }

    public void setLegacyStatus(String legacyStatus) {
        this.legacyStatus = legacyStatus;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }
}
