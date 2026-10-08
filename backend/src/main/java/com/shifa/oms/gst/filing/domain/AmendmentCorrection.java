package com.shifa.oms.gst.filing.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * A single post-filing correction to one original document, ready to be reported as a GSTR-1
 * amendment in an open target period (GST returns &amp; filing, Reqs 3.4, 3.8).
 *
 * <p>It captures the natural key of an amendment — the {@link #targetPeriod} the correction is
 * reported in and the {@link #originalDocumentRef} it corrects — together with the
 * {@link #originalPeriod Filed_Period} the document was originally filed in (Req 3.4) and both
 * monetary figures the amendment must carry:
 * <ul>
 *   <li>{@link #originalValue} — the value <em>originally filed</em> for the document, retained so
 *       the amendment is fully auditable (Req 3.4);</li>
 *   <li>{@link #correctedValue} — the <em>latest</em> recomputed value for the document (Req 3.8).</li>
 * </ul>
 *
 * <p>This is the pure value type consumed by {@link AmendmentConsolidator}; the JPA
 * {@code ReturnAmendment} entity that persists it is separate. Pure and Spring-free; no JPA. Both
 * monetary components are normalised to scale-2.
 *
 * @param targetPeriod        the open (non-FILED) period the amendment is reported in (Req 3.3)
 * @param originalPeriod      the Filed_Period the corrected document was originally filed in (Req 3.4)
 * @param originalDocumentRef the original document being corrected (order code / note number, Req 3.4)
 * @param originalValue       the value originally filed for the document, scale-2 (Req 3.4)
 * @param correctedValue      the latest recomputed value for the document, scale-2 (Req 3.8)
 */
public record AmendmentCorrection(
        ReturnPeriod targetPeriod,
        ReturnPeriod originalPeriod,
        String originalDocumentRef,
        BigDecimal originalValue,
        BigDecimal correctedValue) {

    /** Validates required fields and normalises the monetary components to scale-2. */
    public AmendmentCorrection {
        Objects.requireNonNull(targetPeriod, "targetPeriod");
        Objects.requireNonNull(originalPeriod, "originalPeriod");
        Objects.requireNonNull(originalDocumentRef, "originalDocumentRef");
        Objects.requireNonNull(originalValue, "originalValue");
        Objects.requireNonNull(correctedValue, "correctedValue");
        if (originalDocumentRef.isBlank()) {
            throw new IllegalArgumentException("originalDocumentRef must not be blank");
        }
        originalValue = originalValue.setScale(2, RoundingMode.HALF_UP);
        correctedValue = correctedValue.setScale(2, RoundingMode.HALF_UP);
    }
}
