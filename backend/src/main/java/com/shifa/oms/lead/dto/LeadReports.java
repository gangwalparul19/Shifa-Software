package com.shifa.oms.lead.dto;

import com.shifa.oms.lead.LeadStatus;
import com.shifa.oms.lead.LostReason;
import com.shifa.oms.order.LeadSource;

import java.math.BigDecimal;
import java.util.List;

/**
 * Read-model DTOs for the lead reports served from {@code /api/leads/reports/*}
 * (Requirement 6, design &sect;Reporting). Each is a thin projection over the
 * pure {@code LeadReportAggregator} output so the aggregator stays free of web
 * concerns and directly property-testable.
 */
public final class LeadReports {

    private LeadReports() {
    }

    /** One row of the leads-by-source report: a channel and its lead count in range. */
    public record SourceCount(LeadSource source, long count) {
    }

    /** The leads-by-source report (Req 6.1): counts grouped by {@link LeadSource}. */
    public record BySourceReport(List<SourceCount> rows, long total) {
    }

    /**
     * One conversion row (Req 6.2): a grouping key (source name or owner id), the
     * number of leads captured in range, the number that reached {@code WON}, and
     * {@code conversionRate = won / leads} as a percentage (0 when {@code leads = 0}).
     */
    public record ConversionRow(String key, long leads, long won, BigDecimal conversionRate) {
    }

    /**
     * The conversion report (Req 6.2): conversion rows grouped by source and by
     * owner, each summing to the in-range lead count.
     */
    public record ConversionReport(List<ConversionRow> bySource, List<ConversionRow> byOwner) {
    }

    /** One pipeline-snapshot row: an active {@link LeadStatus} and its current count. */
    public record PipelineCount(LeadStatus status, long count) {
    }

    /** The pipeline-snapshot report (Req 6.3): current active-lead counts per stage. */
    public record PipelineReport(List<PipelineCount> rows, long total) {
    }

    /** One lost-reasons row: a {@link LostReason} and its count in range. */
    public record LostReasonCount(LostReason reason, long count) {
    }

    /** The lost-reasons report (Req 6.4): LOST-lead counts grouped by {@link LostReason}. */
    public record LostReasonReport(List<LostReasonCount> rows, long total) {
    }

    // --- CRM expansion Phase 2 (Req 13): by-stage / by-user / by-team -------

    /**
     * One stage-report row (CRM expansion Req 13.1): a {@link LeadStatus} stage
     * and the number of in-range leads currently in it.
     */
    public record StageCount(LeadStatus stage, long count) {
    }

    /** The by-stage report (Req 13.1): in-range lead counts grouped by stage. */
    public record ByStageReport(List<StageCount> rows, long total) {
    }

    /**
     * One user/team report row (CRM expansion Req 13.2): a grouping key (owner id
     * / team-lead id, or a display label), the in-range lead count, the number
     * that reached {@code WON}, {@code conversionRate = won / leads}, and the
     * revenue of the converted leads in the group.
     */
    public record GroupPerformanceRow(
            String key,
            String label,
            long leads,
            long won,
            BigDecimal conversionRate,
            BigDecimal convertedRevenue) {
    }

    /** The by-user / by-team report (Req 13.2): grouped performance rows. */
    public record GroupPerformanceReport(List<GroupPerformanceRow> rows, long total) {
    }
}
