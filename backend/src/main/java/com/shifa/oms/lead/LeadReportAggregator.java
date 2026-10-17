package com.shifa.oms.lead;

import com.shifa.oms.lead.dto.LeadReports.ByStageReport;
import com.shifa.oms.lead.dto.LeadReports.BySourceReport;
import com.shifa.oms.lead.dto.LeadReports.ConversionReport;
import com.shifa.oms.lead.dto.LeadReports.ConversionRow;
import com.shifa.oms.lead.dto.LeadReports.GroupPerformanceReport;
import com.shifa.oms.lead.dto.LeadReports.GroupPerformanceRow;
import com.shifa.oms.lead.dto.LeadReports.LostReasonCount;
import com.shifa.oms.lead.dto.LeadReports.LostReasonReport;
import com.shifa.oms.lead.dto.LeadReports.PipelineCount;
import com.shifa.oms.lead.dto.LeadReports.PipelineReport;
import com.shifa.oms.lead.dto.LeadReports.SourceCount;
import com.shifa.oms.lead.dto.LeadReports.StageCount;
import com.shifa.oms.order.LeadSource;
import com.shifa.oms.reporting.domain.DateRange;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure lead-report aggregation over a set of {@link LeadReportRecord}s (design
 * &sect;Reporting), mirroring the order module's {@code ReportAggregator}. It
 * holds no persistence or web concerns: it takes plain lead projections and a
 * {@link DateRange} window and computes the leads-by-source, conversion
 * (per source and per owner), pipeline-snapshot, and lost-reason reports.
 *
 * <p>Salesperson scoping is applied upstream by the service (the records handed
 * in are already the caller's visible leads); this class only groups and counts.
 * Keeping it pure is what lets Property 9 exercise it directly over generated
 * inputs (design &sect;Correctness Properties (9)).
 *
 * <p>The core invariants: an in-range record participates in the date-windowed
 * reports iff its {@link LeadReportRecord#createdDate()} is within the window;
 * per-source / per-owner {@code conversionRate = won / leads} (0 when
 * {@code leads = 0}); and the group counts partition the in-range leads exactly.
 */
public class LeadReportAggregator {

    /** Scale of the reported conversion rate (a fraction in {@code [0, 1]}). */
    private static final int RATE_SCALE = 4;

    /** The label a {@code null} owner id is reported under. */
    public static final String UNSPECIFIED_OWNER = "UNSPECIFIED";

    /** The active pipeline stages, always present in the pipeline snapshot. */
    private static final List<LeadStatus> ACTIVE_STAGES =
            List.of(LeadStatus.NEW, LeadStatus.CONTACTED, LeadStatus.QUOTED);

    /** The leads whose capture date falls within the window (design &sect;Reporting). */
    public List<LeadReportRecord> within(List<LeadReportRecord> leads, DateRange window) {
        List<LeadReportRecord> result = new ArrayList<>();
        for (LeadReportRecord l : leads) {
            if (window.contains(l.createdDate())) {
                result.add(l);
            }
        }
        return result;
    }

    /**
     * Leads grouped by {@link LeadSource} over the window (Req 6.1), ordered by
     * descending count then source name. The row counts sum to the in-range lead
     * count.
     */
    public BySourceReport bySource(List<LeadReportRecord> leads, DateRange window) {
        Map<LeadSource, Long> counts = new LinkedHashMap<>();
        long total = 0;
        for (LeadReportRecord l : within(leads, window)) {
            counts.merge(l.source(), 1L, Long::sum);
            total++;
        }
        List<SourceCount> rows = new ArrayList<>();
        counts.forEach((source, count) -> rows.add(new SourceCount(source, count)));
        rows.sort(Comparator.comparingLong(SourceCount::count).reversed()
                .thenComparing(r -> r.source() == null ? "" : r.source().name()));
        return new BySourceReport(rows, total);
    }

    /**
     * The conversion report over the window (Req 6.2): rows grouped by source and
     * by owner. Each row's {@code leads} counts the in-range leads in that group,
     * {@code won} counts those in {@link LeadStatus#WON}, and
     * {@code conversionRate = won / leads} (0 when {@code leads = 0}). The row
     * {@code leads} sum, for either grouping, equals the in-range lead count.
     */
    public ConversionReport conversion(List<LeadReportRecord> leads, DateRange window) {
        Map<String, long[]> bySource = new LinkedHashMap<>();
        Map<String, long[]> byOwner = new LinkedHashMap<>();
        for (LeadReportRecord l : within(leads, window)) {
            String sourceKey = l.source() == null ? "" : l.source().name();
            String ownerKey = l.ownerUserId() == null
                    ? UNSPECIFIED_OWNER : Long.toString(l.ownerUserId());
            boolean won = l.status() == LeadStatus.WON;
            accumulate(bySource, sourceKey, won);
            accumulate(byOwner, ownerKey, won);
        }
        return new ConversionReport(toConversionRows(bySource), toConversionRows(byOwner));
    }

    /**
     * The pipeline snapshot (Req 6.3): current active-lead counts per stage
     * (NEW / CONTACTED / QUOTED), always present (0 when empty); terminal leads
     * are excluded. Not date-windowed — it is a "right now" view.
     */
    public PipelineReport pipeline(List<LeadReportRecord> leads) {
        Map<LeadStatus, Long> counts = new LinkedHashMap<>();
        for (LeadStatus stage : ACTIVE_STAGES) {
            counts.put(stage, 0L);
        }
        long total = 0;
        for (LeadReportRecord l : leads) {
            if (counts.containsKey(l.status())) {
                counts.merge(l.status(), 1L, Long::sum);
                total++;
            }
        }
        List<PipelineCount> rows = new ArrayList<>();
        for (LeadStatus stage : ACTIVE_STAGES) {
            rows.add(new PipelineCount(stage, counts.get(stage)));
        }
        return new PipelineReport(rows, total);
    }

    /**
     * Lost leads grouped by {@link LostReason} over the window (Req 6.4), ordered
     * by descending count then reason name. Only leads in {@link LeadStatus#LOST}
     * with a non-null reason participate; the row counts sum to that LOST count.
     */
    public LostReasonReport lostReasons(List<LeadReportRecord> leads, DateRange window) {
        Map<LostReason, Long> counts = new LinkedHashMap<>();
        long total = 0;
        for (LeadReportRecord l : within(leads, window)) {
            if (l.status() == LeadStatus.LOST && l.lostReason() != null) {
                counts.merge(l.lostReason(), 1L, Long::sum);
                total++;
            }
        }
        List<LostReasonCount> rows = new ArrayList<>();
        counts.forEach((reason, count) -> rows.add(new LostReasonCount(reason, count)));
        rows.sort(Comparator.comparingLong(LostReasonCount::count).reversed()
                .thenComparing(r -> r.reason().name()));
        return new LostReasonReport(rows, total);
    }

    // --- CRM expansion Phase 2 (Req 13): by-stage / by-user / by-team -------

    /**
     * In-range leads grouped by current {@link LeadStatus} stage (CRM expansion
     * Req 13.1), ordered by lifecycle (NEW…LOST). Unlike {@link #pipeline}, this
     * covers ALL stages (incl. terminal WON/LOST) and is date-windowed.
     */
    public ByStageReport byStage(List<LeadReportRecord> leads, DateRange window) {
        Map<LeadStatus, Long> counts = new java.util.EnumMap<>(LeadStatus.class);
        long total = 0;
        for (LeadReportRecord l : within(leads, window)) {
            if (l.status() != null) {
                counts.merge(l.status(), 1L, Long::sum);
                total++;
            }
        }
        List<StageCount> rows = new ArrayList<>();
        for (LeadStatus stage : LeadStatus.values()) { // lifecycle (declaration) order
            long c = counts.getOrDefault(stage, 0L);
            if (c > 0) {
                rows.add(new StageCount(stage, c));
            }
        }
        return new ByStageReport(rows, total);
    }

    /**
     * In-range leads grouped by assignee (owner user id) with conversion + the
     * converted-lead revenue (CRM expansion Req 13.2), ordered by descending lead
     * count then key. {@code labels} maps an owner id (as string) to a display
     * name; a missing/{@code null} owner is reported under {@link #UNSPECIFIED_OWNER}.
     */
    public GroupPerformanceReport byUser(List<LeadReportRecord> leads, DateRange window,
                                         Map<String, String> labels) {
        return grouped(within(leads, window),
                l -> l.ownerUserId() == null ? UNSPECIFIED_OWNER : Long.toString(l.ownerUserId()),
                labels);
    }

    /**
     * In-range leads grouped by TEAM with conversion + converted revenue (CRM
     * expansion Req 13.2). {@code ownerToTeam} maps an owner id to its team key
     * (e.g. the team-lead id as string); owners without a team fall under
     * {@link #UNSPECIFIED_OWNER}. {@code labels} maps a team key to a display name.
     */
    public GroupPerformanceReport byTeam(List<LeadReportRecord> leads, DateRange window,
                                         Map<Long, String> ownerToTeam, Map<String, String> labels) {
        return grouped(within(leads, window),
                l -> {
                    String team = l.ownerUserId() == null ? null : ownerToTeam.get(l.ownerUserId());
                    return team == null ? UNSPECIFIED_OWNER : team;
                },
                labels);
    }

    /** Shared grouping for the by-user / by-team performance reports. */
    private GroupPerformanceReport grouped(List<LeadReportRecord> inRange,
                                           java.util.function.Function<LeadReportRecord, String> keyOf,
                                           Map<String, String> labels) {
        // key -> [leads, won], and key -> converted revenue.
        Map<String, long[]> counts = new LinkedHashMap<>();
        Map<String, BigDecimal> revenue = new LinkedHashMap<>();
        long total = 0;
        for (LeadReportRecord l : inRange) {
            String key = keyOf.apply(l);
            long[] cell = counts.computeIfAbsent(key, k -> new long[2]);
            cell[0]++;
            total++;
            if (l.status() == LeadStatus.WON) {
                cell[1]++;
                BigDecimal rev = l.convertedRevenue() == null ? BigDecimal.ZERO : l.convertedRevenue();
                revenue.merge(key, rev, BigDecimal::add);
            }
        }
        List<GroupPerformanceRow> rows = new ArrayList<>();
        for (Map.Entry<String, long[]> e : counts.entrySet()) {
            long groupLeads = e.getValue()[0];
            long won = e.getValue()[1];
            String label = labels == null ? e.getKey() : labels.getOrDefault(e.getKey(), e.getKey());
            rows.add(new GroupPerformanceRow(e.getKey(), label, groupLeads, won,
                    conversionRate(won, groupLeads),
                    revenue.getOrDefault(e.getKey(), BigDecimal.ZERO)));
        }
        rows.sort(Comparator.comparingLong(GroupPerformanceRow::leads).reversed()
                .thenComparing(GroupPerformanceRow::key));
        return new GroupPerformanceReport(rows, total);
    }

    /**
     * The conversion rate {@code won / leads} as a fraction in {@code [0, 1]},
     * or {@code 0} when {@code leads = 0} (design &sect;Reporting). Exposed so the
     * property test can assert the exact formula.
     */
    public static BigDecimal conversionRate(long won, long leads) {
        if (leads == 0) {
            return BigDecimal.ZERO.setScale(RATE_SCALE, RoundingMode.HALF_UP);
        }
        return BigDecimal.valueOf(won)
                .divide(BigDecimal.valueOf(leads), RATE_SCALE, RoundingMode.HALF_UP);
    }

    private static void accumulate(Map<String, long[]> groups, String key, boolean won) {
        long[] cell = groups.computeIfAbsent(key, k -> new long[2]);
        cell[0]++;              // leads
        if (won) {
            cell[1]++;          // won
        }
    }

    /** Orders a {leads, won} group map by key into conversion rows with exact rates. */
    private static List<ConversionRow> toConversionRows(Map<String, long[]> groups) {
        List<ConversionRow> rows = new ArrayList<>();
        for (Map.Entry<String, long[]> e : groups.entrySet()) {
            long leads = e.getValue()[0];
            long won = e.getValue()[1];
            rows.add(new ConversionRow(e.getKey(), leads, won, conversionRate(won, leads)));
        }
        rows.sort(Comparator.comparing(ConversionRow::key));
        return rows;
    }
}
