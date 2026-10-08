package com.shifa.oms.insights.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A single computed insight (design &sect;Pure domain) — immutable and free of
 * any persistence or web concern, so the pure {@code InsightEngine} can be
 * exercised directly by the jqwik property tests.
 *
 * <p>The stable <em>natural key</em> {@code (type, scope, scopeRefId, computedDate)}
 * — exposed via {@link #naturalKey()} — is what makes persistence idempotent and
 * notification de-duplication possible: recomputing the same date yields insights
 * with the same keys. GLOBAL-scope insights carry the sentinel {@code scopeRefId = 0}
 * (rather than {@code null}) so the natural key stays unique per {@code type + date};
 * the persistence factory also defensively maps a {@code null} ref to {@code 0}.
 *
 * @param type         the insight family
 * @param scope        the entity kind this insight is about
 * @param scopeRefId   the referenced row id ({@code 0} sentinel for GLOBAL scope)
 * @param scopeLabel   a human-friendly label for the scoped entity (may be null)
 * @param severity     the insight severity
 * @param title        a short headline
 * @param detail       a longer explanation (may be null)
 * @param metricValue  the headline numeric value (may be null)
 * @param computedDate the date this insight was computed for
 */
public record Insight(
        InsightType type,
        InsightScope scope,
        Long scopeRefId,
        String scopeLabel,
        InsightSeverity severity,
        String title,
        String detail,
        BigDecimal metricValue,
        LocalDate computedDate) {

    /**
     * The stable natural key {@code (type, scope, scopeRefId, computedDate)} used
     * for idempotent persistence and notification de-dup (design &sect;Pure domain).
     */
    public NaturalKey naturalKey() {
        return new NaturalKey(type, scope, scopeRefId, computedDate);
    }

    /** The identity of an insight across recomputes of the same date. */
    public record NaturalKey(
            InsightType type,
            InsightScope scope,
            Long scopeRefId,
            LocalDate computedDate) {
    }
}
