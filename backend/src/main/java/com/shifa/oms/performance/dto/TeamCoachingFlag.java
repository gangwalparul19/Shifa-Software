package com.shifa.oms.performance.dto;

/** Explainable read-only coaching signal; no private profile/document data. */
public record TeamCoachingFlag(
        Long salespersonId,
        String salespersonName,
        String type,
        String severity,
        String title,
        String detail
) {
}
