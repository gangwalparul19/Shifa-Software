package com.shifa.oms.incentive.domain;

import com.shifa.oms.incentive.domain.RuleTimeline.Tier;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Plain-language description of what a new rule version changes compared with the one it replaces.
 * It is stored on the version and copied into the audit trail, so the history reads as sentences
 * ("Changed the ₹35,000 slab bonus from ₹700 to ₹750") rather than raw numbers.
 */
public final class IncentiveRuleDiff {

    private IncentiveRuleDiff() {
    }

    /**
     * @param effectiveFrom the day the new version starts
     * @param before        the slabs it replaces, or {@code null} when no rules were in force
     * @param after         the new slabs (empty means the daily bonus is off from that day)
     */
    public static String describe(LocalDate effectiveFrom, List<Tier> before, List<Tier> after) {
        String prefix = "From " + effectiveFrom + ": ";
        if (before == null) {
            return after.isEmpty()
                    ? prefix + "no daily bonus slabs (incentives stay off)."
                    : prefix + "first rules set. " + slabs(after) + ".";
        }
        if (after.isEmpty()) {
            return before.isEmpty()
                    ? prefix + "no daily bonus slabs (incentives stay off)."
                    : prefix + "daily bonus switched off. Removed " + slabs(before) + ".";
        }
        Map<BigDecimal, BigDecimal> old = byMinimum(before);
        Map<BigDecimal, BigDecimal> now = byMinimum(after);
        List<String> parts = new ArrayList<>();
        for (Map.Entry<BigDecimal, BigDecimal> e : now.entrySet()) {
            BigDecimal previous = old.get(e.getKey());
            if (previous == null) {
                parts.add("added the " + IncentiveFormat.inr(e.getKey()) + " slab (bonus "
                        + IncentiveFormat.inr(e.getValue()) + ")");
            } else if (previous.compareTo(e.getValue()) != 0) {
                parts.add("changed the " + IncentiveFormat.inr(e.getKey()) + " slab bonus from "
                        + IncentiveFormat.inr(previous) + " to " + IncentiveFormat.inr(e.getValue()));
            }
        }
        for (Map.Entry<BigDecimal, BigDecimal> e : old.entrySet()) {
            if (!now.containsKey(e.getKey())) {
                parts.add("removed the " + IncentiveFormat.inr(e.getKey()) + " slab (bonus "
                        + IncentiveFormat.inr(e.getValue()) + ")");
            }
        }
        if (parts.isEmpty()) {
            return prefix + "saved again with no change to the slabs.";
        }
        String joined = String.join("; ", parts);
        return prefix + Character.toUpperCase(joined.charAt(0)) + joined.substring(1) + ".";
    }

    private static String slabs(List<Tier> tiers) {
        List<String> out = new ArrayList<>();
        for (Tier t : tiers) {
            out.add(IncentiveFormat.inr(t.minSales()) + " and above earns " + IncentiveFormat.inr(t.bonus()));
        }
        return String.join(", ", out);
    }

    /** Slabs keyed by minimum, compared by value so 25000 and 25000.00 are the same slab. */
    private static Map<BigDecimal, BigDecimal> byMinimum(List<Tier> tiers) {
        Map<BigDecimal, BigDecimal> map = new TreeMap<>();
        for (Tier t : tiers) {
            map.put(t.minSales().stripTrailingZeros(), t.bonus());
        }
        return map;
    }
}
