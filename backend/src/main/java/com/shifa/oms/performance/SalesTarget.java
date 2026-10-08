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

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    protected SalesTarget() {
        // Required by JPA.
    }

    public SalesTarget(Long salespersonId, LocalDate periodMonth, BigDecimal targetAmount,
                       BigDecimal incentivePct) {
        this.salespersonId = salespersonId;
        this.periodMonth = periodMonth;
        this.targetAmount = targetAmount;
        this.incentivePct = incentivePct;
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

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
