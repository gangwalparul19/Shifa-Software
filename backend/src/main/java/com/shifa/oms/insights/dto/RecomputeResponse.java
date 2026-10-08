package com.shifa.oms.insights.dto;

import java.time.LocalDate;

/**
 * The response of the admin {@code POST /api/insights/recompute} endpoint
 * (statistical-insights-engine, design §API): how many insights were persisted
 * and the date they were computed for.
 *
 * @param computed     the number of insights persisted by the run
 * @param computedDate the date the insights were computed for
 */
public record RecomputeResponse(int computed, LocalDate computedDate) {
}
