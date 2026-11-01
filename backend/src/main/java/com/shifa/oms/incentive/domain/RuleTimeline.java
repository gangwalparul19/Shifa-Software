package com.shifa.oms.incentive.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Every saved version of the daily incentive rules, answering "what was the bonus rule on this
 * day?". Pure: no database, no clock.
 *
 * <p>A version applies from its {@code effectiveFrom} day until a later version takes over. When
 * two versions share a day, the one saved later (higher id) wins. Because each day is judged by the
 * version in force on that day, changing the rules never rewrites bonuses for earlier days.
 */
public final class RuleTimeline {

    /** One slab: reaching {@code minSales} in a day earns {@code bonus}. */
    public record Tier(BigDecimal minSales, BigDecimal bonus) {
    }

    /** One saved rule set. {@code tiers} are in ascending order of {@code minSales}. */
    public record Version(long id, LocalDate effectiveFrom, List<Tier> tiers) {
        public Version {
            List<Tier> sorted = new ArrayList<>(tiers == null ? List.of() : tiers);
            sorted.sort(Comparator.comparing(Tier::minSales));
            tiers = List.copyOf(sorted);
        }
    }

    /** The bonus a day earned and the slab that earned it ({@code slab} is null when none did). */
    public record Bonus(BigDecimal amount, BigDecimal slab) {
        public static final Bonus NONE = new Bonus(BigDecimal.ZERO.setScale(2), null);

        public boolean earned() {
            return amount.signum() > 0;
        }
    }

    /** Versions ordered by effective day, then by id, so the last qualifying one is the winner. */
    private final List<Version> versions;

    public RuleTimeline(Collection<Version> versions) {
        List<Version> sorted = new ArrayList<>(versions == null ? List.of() : versions);
        sorted.sort(Comparator.comparing(Version::effectiveFrom).thenComparingLong(Version::id));
        this.versions = List.copyOf(sorted);
    }

    /** The version in force on {@code date}, or empty when no version has started by then. */
    public Optional<Version> inForce(LocalDate date) {
        Version winner = null;
        for (Version version : versions) {
            if (version.effectiveFrom().isAfter(date)) {
                break;
            }
            winner = version;
        }
        return Optional.ofNullable(winner);
    }

    /** Whether any slab is in force on {@code date}. */
    public boolean hasSlabsOn(LocalDate date) {
        return inForce(date).map(version -> !version.tiers().isEmpty()).orElse(false);
    }

    /**
     * The bonus for a day with {@code sales}: the highest slab the sales reach (slabs do not
     * stack), where reaching a slab means sales are at least its minimum.
     */
    public Bonus bonusFor(LocalDate date, BigDecimal sales) {
        if (sales == null || sales.signum() <= 0) {
            return Bonus.NONE;
        }
        Tier reached = null;
        for (Tier tier : inForce(date).map(Version::tiers).orElse(List.of())) {
            if (sales.compareTo(tier.minSales()) >= 0) {
                reached = tier;
            }
        }
        if (reached == null) {
            return Bonus.NONE;
        }
        return new Bonus(reached.bonus().setScale(2, RoundingMode.HALF_UP), reached.minSales());
    }
}
