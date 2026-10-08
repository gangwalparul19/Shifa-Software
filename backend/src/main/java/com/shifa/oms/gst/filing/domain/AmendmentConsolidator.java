package com.shifa.oms.gst.filing.domain;

import java.util.Objects;

/**
 * Consolidates post-filing corrections so there is exactly one amendment per
 * {@code (targetPeriod, originalDocument)} pair, always carrying the <strong>latest</strong>
 * recomputed corrected value while retaining the value <em>originally filed</em>
 * (GST returns &amp; filing, Reqs 3.4, 3.8).
 *
 * <p>When more than one correction is detected for the same original document within the same
 * target period, they must collapse into a single amendment reflecting the latest computed figures
 * for that document (Req 3.8). This class encodes that merge as a pure function:
 * <ul>
 *   <li>when no amendment yet exists for the pair, the new correction stands alone;</li>
 *   <li>when one already exists, the result keeps the existing amendment's originally filed value
 *       ({@link AmendmentCorrection#originalValue}) and replaces its corrected value with the new
 *       correction's latest {@link AmendmentCorrection#correctedValue}.</li>
 * </ul>
 *
 * <p>Pure and Spring-free; no JPA. The persistence layer is expected to look up any existing
 * amendment by the consolidation key and pass it here to obtain the value to upsert.
 */
public final class AmendmentConsolidator {

    private AmendmentConsolidator() {
    }

    /**
     * Merges a newly detected correction with the existing amendment (if any) for the same
     * {@code (targetPeriod, originalDocumentRef)} pair (Reqs 3.4, 3.8).
     *
     * @param existingForDocInTarget the amendment already recorded for this pair, or {@code null}
     *     when none exists yet
     * @param newCorrection          the newly detected correction carrying the latest recomputed
     *     figure (must not be {@code null})
     * @return the consolidated amendment: {@code newCorrection} unchanged when there is no existing
     *     amendment; otherwise an {@link AmendmentCorrection} that retains the existing amendment's
     *     originally filed value and adopts the new correction's latest corrected value
     * @throws NullPointerException     if {@code newCorrection} is {@code null}
     * @throws IllegalArgumentException if {@code existingForDocInTarget} is present but does not share
     *     the same {@code (targetPeriod, originalDocumentRef)} consolidation key as
     *     {@code newCorrection}
     */
    public static AmendmentCorrection merge(
            AmendmentCorrection existingForDocInTarget, AmendmentCorrection newCorrection) {
        Objects.requireNonNull(newCorrection, "newCorrection");
        if (existingForDocInTarget == null) {
            return newCorrection;
        }
        if (!existingForDocInTarget.targetPeriod().equals(newCorrection.targetPeriod())
                || !existingForDocInTarget.originalDocumentRef().equals(newCorrection.originalDocumentRef())) {
            throw new IllegalArgumentException(
                    "existing amendment and new correction must share the same (targetPeriod, originalDocumentRef)");
        }
        return new AmendmentCorrection(
                existingForDocInTarget.targetPeriod(),
                existingForDocInTarget.originalPeriod(),
                existingForDocInTarget.originalDocumentRef(),
                existingForDocInTarget.originalValue(),
                newCorrection.correctedValue());
    }
}
