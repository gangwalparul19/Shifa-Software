package com.shifa.oms.insights;

import com.shifa.oms.insights.domain.Insight;
import com.shifa.oms.insights.domain.InsightScope;
import com.shifa.oms.insights.domain.InsightSeverity;
import com.shifa.oms.insights.domain.InsightType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A persisted computed insight, mapped to the {@code insights} table (V26,
 * design &sect;Persistence, &sect;Data Model). It is the durable projection of a
 * pure {@link Insight}: the nightly batch (and the on-demand recompute) writes
 * one row per {@code (insight_type, scope, scope_ref_id, computed_date)} natural
 * key, and the dashboard/read API serve from here instantly.
 *
 * <p>The three enums persist as their {@code @Enumerated} STRING names (matching
 * the VARCHAR check-constrained columns); {@code created_at} is filled by the DB
 * default and is therefore not written on insert/update (following the
 * {@code LeadEntity}/{@code OrderEntity} pattern). GLOBAL-scope insights carry
 * the sentinel {@code scope_ref_id = 0} set by {@link #from(Insight)} so the
 * unique natural key stays unique per {@code type + date}.
 */
@Entity
@Table(name = "insights")
public class InsightEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "insight_type", nullable = false, length = 40)
    private InsightType insightType;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope", nullable = false, length = 20)
    private InsightScope scope;

    @Column(name = "scope_ref_id")
    private Long scopeRefId;

    @Column(name = "scope_label", length = 200)
    private String scopeLabel;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false, length = 20)
    private InsightSeverity severity;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "detail", columnDefinition = "text")
    private String detail;

    @Column(name = "metric_value", precision = 18, scale = 4)
    private BigDecimal metricValue;

    @Column(name = "computed_date", nullable = false)
    private LocalDate computedDate;

    @Column(name = "dismissed", nullable = false)
    private boolean dismissed = false;

    @Column(name = "dismissed_at")
    private LocalDateTime dismissedAt;

    @Column(name = "dismissed_by")
    private Long dismissedBy;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    protected InsightEntity() {
        // Required by JPA.
    }

    /**
     * Projects a pure {@link Insight} into a persistable row. A GLOBAL-scope
     * insight (or any {@code null} {@code scopeRefId}) is stored with the sentinel
     * {@code scope_ref_id = 0} so the {@code ux_insights_natural} unique index
     * stays unique per {@code type + date}.
     */
    public static InsightEntity from(Insight insight) {
        InsightEntity e = new InsightEntity();
        e.insightType = insight.type();
        e.scope = insight.scope();
        e.scopeRefId = (insight.scope() == InsightScope.GLOBAL || insight.scopeRefId() == null)
                ? 0L : insight.scopeRefId();
        e.scopeLabel = insight.scopeLabel();
        e.severity = insight.severity();
        e.title = insight.title();
        e.detail = insight.detail();
        e.metricValue = insight.metricValue();
        e.computedDate = insight.computedDate();
        e.dismissed = false;
        return e;
    }

    /** Marks this insight dismissed, stamping who/when the first time only (idempotent). */
    public void markDismissed(Long userId, LocalDateTime at) {
        if (!this.dismissed) {
            this.dismissed = true;
            this.dismissedBy = userId;
            this.dismissedAt = at;
        }
    }

    public Long getId() {
        return id;
    }

    public InsightType getInsightType() {
        return insightType;
    }

    public InsightScope getScope() {
        return scope;
    }

    public Long getScopeRefId() {
        return scopeRefId;
    }

    public String getScopeLabel() {
        return scopeLabel;
    }

    public InsightSeverity getSeverity() {
        return severity;
    }

    public String getTitle() {
        return title;
    }

    public String getDetail() {
        return detail;
    }

    public BigDecimal getMetricValue() {
        return metricValue;
    }

    public LocalDate getComputedDate() {
        return computedDate;
    }

    public boolean isDismissed() {
        return dismissed;
    }

    public LocalDateTime getDismissedAt() {
        return dismissedAt;
    }

    public Long getDismissedBy() {
        return dismissedBy;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
