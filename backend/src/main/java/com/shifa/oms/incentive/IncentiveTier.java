package com.shifa.oms.incentive;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;

/** One slab of a rule set: reach {@code minDailySales} in a day and earn {@code bonusAmount}. */
@Entity
@Table(name = "incentive_tiers")
public class IncentiveTier {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "rule_set_id", nullable = false)
    private IncentiveRuleSet ruleSet;

    /** Daily sales needed for this slab, inclusive. */
    @Column(name = "min_daily_sales", nullable = false, precision = 12, scale = 2)
    private BigDecimal minDailySales;

    @Column(name = "bonus_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal bonusAmount;

    protected IncentiveTier() {
        // Required by JPA.
    }

    public IncentiveTier(BigDecimal minDailySales, BigDecimal bonusAmount) {
        this.minDailySales = minDailySales;
        this.bonusAmount = bonusAmount;
    }

    void setRuleSet(IncentiveRuleSet ruleSet) { this.ruleSet = ruleSet; }

    public Long getId() { return id; }
    public IncentiveRuleSet getRuleSet() { return ruleSet; }
    public BigDecimal getMinDailySales() { return minDailySales; }
    public BigDecimal getBonusAmount() { return bonusAmount; }
}
