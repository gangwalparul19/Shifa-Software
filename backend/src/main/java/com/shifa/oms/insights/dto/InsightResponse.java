package com.shifa.oms.insights.dto;

import com.shifa.oms.insights.InsightEntity;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * The read DTO for a persisted insight served by {@code GET /api/insights}
 * (statistical-insights-engine, design §API). Flattens {@link InsightEntity}'s
 * enums to their names so the payload is stable and frontend-friendly.
 *
 * @param id           the persisted insight id
 * @param type         the {@code InsightType} name
 * @param scope        the {@code InsightScope} name
 * @param scopeRefId   the referenced row id (sentinel {@code 0} for GLOBAL)
 * @param scopeLabel   a human-friendly label for the scoped entity (may be null)
 * @param severity     the {@code InsightSeverity} name
 * @param title        the short headline
 * @param detail       the longer explanation (may be null)
 * @param metricValue  the headline numeric value (may be null)
 * @param computedDate the date the insight was computed for
 * @param dismissed    whether the insight has been dismissed
 */
public record InsightResponse(
        Long id,
        String type,
        String scope,
        Long scopeRefId,
        String scopeLabel,
        String severity,
        String title,
        String detail,
        BigDecimal metricValue,
        LocalDate computedDate,
        boolean dismissed) {

    /** Projects a persisted {@link InsightEntity} into its read DTO. */
    public static InsightResponse from(InsightEntity e) {
        return new InsightResponse(
                e.getId(),
                e.getInsightType() == null ? null : e.getInsightType().name(),
                e.getScope() == null ? null : e.getScope().name(),
                e.getScopeRefId(),
                e.getScopeLabel(),
                e.getSeverity() == null ? null : e.getSeverity().name(),
                e.getTitle(),
                e.getDetail(),
                e.getMetricValue(),
                e.getComputedDate(),
                e.isDismissed());
    }
}
