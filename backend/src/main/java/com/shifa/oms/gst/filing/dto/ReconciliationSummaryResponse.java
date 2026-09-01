package com.shifa.oms.gst.filing.dto;

import com.shifa.oms.gst.filing.ReconciliationService.ReconciliationSummary;

import java.util.List;

/**
 * The reconciliation summary for a return period for the API (GST returns &amp; filing, Reqs 9.1,
 * 9.2, 9.6): the five compared figures and whether the whole period is reconciled (every compared
 * figure within tolerance). Mirrors the service's {@link ReconciliationSummary}.
 *
 * @param month            the calendar month, 1–12
 * @param year             the four-digit calendar year
 * @param periodReconciled {@code true} iff every compared figure is within tolerance (Req 9.6)
 * @param figures          the compared figures in presentation order
 */
public record ReconciliationSummaryResponse(
        int month,
        int year,
        boolean periodReconciled,
        List<ReconciliationFigureResponse> figures) {

    /**
     * Maps the service {@link ReconciliationSummary} to the API payload.
     *
     * @param summary the reconciliation summary from {@code ReconciliationService}
     * @return the response payload
     */
    public static ReconciliationSummaryResponse from(ReconciliationSummary summary) {
        return new ReconciliationSummaryResponse(
                summary.month(),
                summary.year(),
                summary.periodReconciled(),
                summary.figures().stream().map(ReconciliationFigureResponse::from).toList());
    }
}
