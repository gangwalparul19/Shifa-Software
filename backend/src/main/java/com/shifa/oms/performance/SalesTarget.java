package com.shifa.oms.performance;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A salesperson's monthly sales target + optional incentive rate
 * (FEATURE-ROADMAP §6.1), mapped to {@code sales_targets} (V38).
 * {@code periodMonth} is the first day of the target month.
 */
@Entity
@Table(name = "sales_targets")
public class SalesTarget {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "salesperson_id", nullable = false)
    private Long salespersonId;

    @Column(name = "period_month", nullable = false)
    private LocalDate periodMonth;

    @Column(name = "target_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal targetAmount;

    @Column(name = "incentive_pct", precision = 5, scale = 2)
    private BigDecimal incentivePct;

    /**
     * The target horizon (CRM expansion Phase 2, Req 12): {@code MONTH} (default,
     * the shipped behaviour) or {@code WEEK} anchored on {@link #periodMonth} =
     * that week's Monday. Persisted as the enum name; defaults MONTH so existing
     * rows are unchanged.
     */
    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    @Column(name = "period_type", nullable = false, length = 10)
    private TargetPeriodType periodType = TargetPeriodType.MONTH;

    /**
     * Non-null = a TEAM target (keyed by the team lead instead of a salesperson,
     * with {@link #salespersonId} null). Null = a per-salesperson target.
     */
    @Column(name = "team_lead_id")
    private Long teamLeadId;

    /** Optional conversion-rate goal (%), independent of the revenue target. */
    @Column(name = "conversion_target", precision = 5, scale = 2)
    private BigDecimal conversionTarget;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    protected SalesTarget() {
        // Required by JPA.
    }

    public SalesTarget(Long salespersonId, LocalDate periodMonth, BigDecimal targetAmount,
                       BigDecimal incentivePct) {
        this(salespersonId, null, TargetPeriodType.MONTH, periodMonth, targetAmount, incentivePct, null);
    }

    /**
     * Full constructor for week/team/conversion targets (CRM expansion Phase 2,
     * Req 12). A per-salesperson target sets {@code salespersonId} + null
     * {@code teamLeadId}; a team target sets {@code teamLeadId} + null
     * {@code salespersonId}. {@code periodMonth} is the period anchor (first of
     * the month for MONTH; that week's Monday for WEEK).
     */
    public SalesTarget(Long salespersonId, Long teamLeadId, TargetPeriodType periodType,
                       LocalDate periodMonth, BigDecimal targetAmount, BigDecimal incentivePct,
                       BigDecimal conversionTarget) {
        this.salespersonId = salespersonId;
        this.teamLeadId = teamLeadId;
        this.periodType = periodType == null ? TargetPeriodType.MONTH : periodType;
        this.periodMonth = periodMonth;
        this.targetAmount = targetAmount;
        this.incentivePct = incentivePct;
        this.conversionTarget = conversionTarget;
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public Long getSalespersonId() {
        return salespersonId;
    }

    public LocalDate getPeriodMonth() {
        return periodMonth;
    }

    public BigDecimal getTargetAmount() {
        return targetAmount;
    }

    public void setTargetAmount(BigDecimal targetAmount) {
        this.targetAmount = targetAmount;
        this.updatedAt = LocalDateTime.now();
    }

    public BigDecimal getIncentivePct() {
        return incentivePct;
    }

    public void setIncentivePct(BigDecimal incentivePct) {
        this.incentivePct = incentivePct;
        this.updatedAt = LocalDateTime.now();
    }

    public TargetPeriodType getPeriodType() {
        return periodType;
    }

    public void setPeriodType(TargetPeriodType periodType) {
        this.periodType = periodType;
        this.updatedAt = LocalDateTime.now();
    }

    public Long getTeamLeadId() {
        return teamLeadId;
    }

    public void setTeamLeadId(Long teamLeadId) {
        this.teamLeadId = teamLeadId;
        this.updatedAt = LocalDateTime.now();
    }

    public BigDecimal getConversionTarget() {
        return conversionTarget;
    }

    public void setConversionTarget(BigDecimal conversionTarget) {
        this.conversionTarget = conversionTarget;
        this.updatedAt = LocalDateTime.now();
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
