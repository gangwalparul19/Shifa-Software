package com.shifa.oms.incentive;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * One saved version of the daily incentive rules, mapped to {@code incentive_rule_sets} (V108).
 *
 * <p>A rule set is never edited after it is saved. Changing the rules saves a new set, so every
 * earlier set stays on record with who saved it, when, and what changed: that is the audit trail.
 * The bonus for a day uses the set in force on that day.
 */
@Entity
@Table(name = "incentive_rule_sets")
public class IncentiveRuleSet {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** First day these rules apply (inclusive, Asia/Kolkata calendar day). */
    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "note", length = 255)
    private String note;

    /** Plain-language description of what this version changed compared with the one it replaced. */
    @Column(name = "change_summary", length = 1000)
    private String changeSummary;

    @Column(name = "created_by_id")
    private Long createdById;

    @Column(name = "created_by_name", length = 100)
    private String createdByName;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @OneToMany(mappedBy = "ruleSet", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("minDailySales ASC")
    private List<IncentiveTier> tiers = new ArrayList<>();

    protected IncentiveRuleSet() {
        // Required by JPA.
    }

    public IncentiveRuleSet(LocalDate effectiveFrom, String note, String changeSummary,
                            Long createdById, String createdByName) {
        this.effectiveFrom = effectiveFrom;
        this.note = note;
        this.changeSummary = changeSummary;
        this.createdById = createdById;
        this.createdByName = createdByName;
        this.createdAt = LocalDateTime.now();
    }

    public void addTier(IncentiveTier tier) {
        tier.setRuleSet(this);
        tiers.add(tier);
    }

    public Long getId() { return id; }
    public LocalDate getEffectiveFrom() { return effectiveFrom; }
    public String getNote() { return note; }
    public String getChangeSummary() { return changeSummary; }
    public Long getCreatedById() { return createdById; }
    public String getCreatedByName() { return createdByName; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public List<IncentiveTier> getTiers() { return tiers; }
}
