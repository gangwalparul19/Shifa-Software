package com.shifa.oms.incentive.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** Request and response shapes for the daily incentive rules (Settings → Incentives). */
public final class IncentiveRules {

    private IncentiveRules() {
    }

    /** A slab as shown to the client. */
    public record TierView(BigDecimal minSales, BigDecimal bonus) {
    }

    /**
     * One saved version of the rules.
     *
     * @param status {@code ACTIVE} (in force today), {@code SCHEDULED} (starts later),
     *               {@code PREVIOUS} (earlier version that has been superseded), or
     *               {@code REPLACED} (saved for the same day as a later version, which won)
     */
    public record RuleVersionView(Long id, LocalDate effectiveFrom, List<TierView> tiers, String note,
                                  String changeSummary, Long createdById, String createdByName,
                                  LocalDateTime createdAt, String status) {
    }

    /** All versions plus the one in force today (null when none has started). */
    public record RulesResponse(LocalDate today, RuleVersionView inForce, List<RuleVersionView> versions) {
    }

    /** A slab in a save request. */
    public record TierRequest(
            @NotNull(message = "Enter the sales amount for every slab.") BigDecimal minSales,
            @NotNull(message = "Enter the bonus for every slab.") BigDecimal bonus) {
    }

    /**
     * Saves a new version of the rules. An empty {@code tiers} list switches the daily bonus off
     * from {@code effectiveFrom}.
     */
    public record RulesRequest(
            @NotNull(message = "Choose the day the rules start.") LocalDate effectiveFrom,
            @Valid List<TierRequest> tiers,
            @Size(max = 255, message = "The note must be at most 255 characters.") String note) {
    }
}
