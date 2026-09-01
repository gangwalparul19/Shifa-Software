package com.shifa.oms.gst.filing.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Detects post-filing GSTR-1 corrections by comparing a stored {@link FilingSnapshot filing
 * snapshot}'s per-section figures against a freshly recomputed set (GST returns &amp; filing,
 * Req 3.1).
 *
 * <p>When an order, return, or refund whose document date falls inside a period whose GSTR-1 has
 * been filed is created, edited, or cancelled, that period's outward sections are recomputed via
 * the existing {@code Gstr1Builder} and compared, section by section, against the figures captured
 * in the current {@link FilingSnapshot}. A section is considered <em>changed</em> when either its
 * taxable value or its tax amount differs from the snapshot by <strong>₹0.01 or more</strong>;
 * differences strictly below ₹0.01 are treated as identical (rounding noise), so no correction is
 * emitted when every section differs by less than ₹0.01.
 *
 * <p>Each per-section pair of figures is modelled by the pure {@link SectionFigures} value type
 * (taxable value + tax amount, both scale-2 {@code BigDecimal}). The two inputs are
 * {@link Map maps} keyed by {@link Gstr1Section}; a section absent from either side is treated as
 * {@linkplain SectionFigures#ZERO zero} on that side, so a section that newly appears or disappears
 * is detected as a change. {@link #detect(Map, Map)} returns the {@link EnumSet set of sections}
 * that changed, in {@link Gstr1Section} declaration order, so the caller can route each one to its
 * amendment table (via {@link AmendmentRouter}) or to manual review.
 *
 * <p>Pure, total, and Spring-free; no JPA. All monetary work is scale-2 {@code HALF_UP}.
 */
public final class CorrectionDetector {

    /** The minimum absolute per-figure difference (₹0.01) that counts as a correction (Req 3.1). */
    public static final BigDecimal THRESHOLD = new BigDecimal("0.01");

    private CorrectionDetector() {
    }

    /**
     * A single section's compared figures: its taxable value and its tax amount, both scale-2.
     *
     * <p>Pure and Spring-free. The compact constructor normalises both components to scale-2
     * {@code HALF_UP} and rejects {@code null}s, so callers may pass values at any scale.
     *
     * @param taxableValue the section's taxable value, scale-2
     * @param taxAmount    the section's tax amount (CGST + SGST + IGST), scale-2
     */
    public record SectionFigures(BigDecimal taxableValue, BigDecimal taxAmount) {

        /** A zero-on-both-components figure, used for a section absent on one side. */
        public static final SectionFigures ZERO =
                new SectionFigures(BigDecimal.ZERO, BigDecimal.ZERO);

        /** Normalises the monetary components to scale-2 and validates required fields. */
        public SectionFigures {
            Objects.requireNonNull(taxableValue, "taxableValue");
            Objects.requireNonNull(taxAmount, "taxAmount");
            taxableValue = taxableValue.setScale(2, RoundingMode.HALF_UP);
            taxAmount = taxAmount.setScale(2, RoundingMode.HALF_UP);
        }

        /**
         * Convenience factory building a {@link SectionFigures} from any numeric inputs.
         *
         * @param taxableValue the section's taxable value (must not be {@code null})
         * @param taxAmount    the section's tax amount (must not be {@code null})
         * @return the scale-2 figures
         */
        public static SectionFigures of(BigDecimal taxableValue, BigDecimal taxAmount) {
            return new SectionFigures(taxableValue, taxAmount);
        }
    }

    /**
     * Compares a stored snapshot's per-section figures against a recomputed set and returns the
     * sections whose taxable value or tax amount differs by ₹0.01 or more (Req 3.1).
     *
     * <p>The union of the two maps' keys is examined; a section missing from either side is treated
     * as {@link SectionFigures#ZERO}. The result is an {@link EnumSet}, so it iterates in
     * {@link Gstr1Section} declaration order and is empty exactly when every section differs by less
     * than ₹0.01.
     *
     * @param snapshotSections   the figures captured in the current GSTR-1 filing snapshot (must not
     *                           be {@code null}; individual values must not be {@code null})
     * @param recomputedSections the freshly recomputed figures for the same period (must not be
     *                           {@code null}; individual values must not be {@code null})
     * @return the set of sections that changed, in declaration order; empty when there is no
     *     correction
     * @throws NullPointerException if either map or any contained value is {@code null}
     */
    public static Set<Gstr1Section> detect(
            Map<Gstr1Section, SectionFigures> snapshotSections,
            Map<Gstr1Section, SectionFigures> recomputedSections) {
        Objects.requireNonNull(snapshotSections, "snapshotSections");
        Objects.requireNonNull(recomputedSections, "recomputedSections");

        Map<Gstr1Section, SectionFigures> snapshot = normalise(snapshotSections);
        Map<Gstr1Section, SectionFigures> recomputed = normalise(recomputedSections);

        Set<Gstr1Section> changed = EnumSet.noneOf(Gstr1Section.class);
        Set<Gstr1Section> allSections = EnumSet.noneOf(Gstr1Section.class);
        allSections.addAll(snapshot.keySet());
        allSections.addAll(recomputed.keySet());
        for (Gstr1Section section : allSections) {
            SectionFigures before = snapshot.getOrDefault(section, SectionFigures.ZERO);
            SectionFigures after = recomputed.getOrDefault(section, SectionFigures.ZERO);
            if (differs(before, after)) {
                changed.add(section);
            }
        }
        return changed;
    }

    /**
     * Whether comparing the two figure sets yields at least one correction (Req 3.1).
     *
     * @param snapshotSections   the figures captured in the current GSTR-1 filing snapshot
     * @param recomputedSections the freshly recomputed figures for the same period
     * @return {@code true} iff {@link #detect(Map, Map)} is non-empty
     */
    public static boolean hasCorrection(
            Map<Gstr1Section, SectionFigures> snapshotSections,
            Map<Gstr1Section, SectionFigures> recomputedSections) {
        return !detect(snapshotSections, recomputedSections).isEmpty();
    }

    private static boolean differs(SectionFigures before, SectionFigures after) {
        return atOrAboveThreshold(before.taxableValue(), after.taxableValue())
                || atOrAboveThreshold(before.taxAmount(), after.taxAmount());
    }

    private static boolean atOrAboveThreshold(BigDecimal before, BigDecimal after) {
        return before.subtract(after).abs().compareTo(THRESHOLD) >= 0;
    }

    private static Map<Gstr1Section, SectionFigures> normalise(
            Map<Gstr1Section, SectionFigures> sections) {
        Map<Gstr1Section, SectionFigures> copy = new EnumMap<>(Gstr1Section.class);
        for (Map.Entry<Gstr1Section, SectionFigures> entry : sections.entrySet()) {
            Gstr1Section key = Objects.requireNonNull(entry.getKey(), "section key");
            SectionFigures value = Objects.requireNonNull(
                    entry.getValue(), () -> "figures for section " + key);
            copy.put(key, value);
        }
        return copy;
    }
}
