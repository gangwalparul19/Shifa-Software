package com.shifa.oms.lead;

import com.shifa.oms.lead.dto.LeadReports.BySourceReport;
import com.shifa.oms.lead.dto.LeadReports.ConversionReport;
import com.shifa.oms.lead.dto.LeadReports.ConversionRow;
import com.shifa.oms.lead.dto.LeadReports.LostReasonCount;
import com.shifa.oms.lead.dto.LeadReports.LostReasonReport;
import com.shifa.oms.lead.dto.LeadReports.PipelineCount;
import com.shifa.oms.lead.dto.LeadReports.PipelineReport;
import com.shifa.oms.lead.dto.LeadReports.SourceCount;
import com.shifa.oms.order.LeadSource;
import com.shifa.oms.reporting.domain.DateRange;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based test for the pure {@link LeadReportAggregator} (design
 * §Correctness Properties (9), §Reporting).
 *
 * Feature: lead-management, Property 9: Conversion-rate report is exact.
 *
 * <p>For any set of leads and any window: per-source / per-owner
 * {@code conversionRate = won / leads} (0 when {@code leads = 0}); the group
 * counts (by-source, and either conversion grouping) sum to the in-range lead
 * count; the lost-reason counts equal the multiset grouping of in-range LOST
 * leads; and the pipeline counts equal the multiset grouping of active leads.
 *
 * <p>Purely exercises the aggregator over generated inputs — no mocks. jqwik
 * default 1000 tries (≥ 100).
 *
 * **Validates: Requirements 6.1, 6.2, 6.3, 6.4**
 */
class LeadReportConversionPropertyTest {

    private static final LeadReportAggregator AGG = new LeadReportAggregator();
    private static final DateRange WINDOW =
            DateRange.of(LocalDate.parse("2024-03-01"), LocalDate.parse("2024-06-30"));
    private static final Set<LeadStatus> ACTIVE =
            EnumSet.of(LeadStatus.NEW, LeadStatus.CONTACTED, LeadStatus.QUOTED);

    // Feature: lead-management, Property 9: Conversion-rate report is exact
    // **Validates: Requirements 6.1, 6.2, 6.3, 6.4**
    @Property
    void conversionAndGroupingsAreExact(@ForAll("leads") List<LeadReportRecord> leads) {

        List<LeadReportRecord> within = new ArrayList<>();
        for (LeadReportRecord l : leads) {
            if (WINDOW.contains(l.createdDate())) {
                within.add(l);
            }
        }
        long inRange = within.size();

        // --- by-source (Req 6.1): counts equal the multiset grouping, sum to in-range. ---
        BySourceReport bySource = AGG.bySource(leads, WINDOW);
        Map<LeadSource, Long> expectedBySource = new LinkedHashMap<>();
        for (LeadReportRecord l : within) {
            expectedBySource.merge(l.source(), 1L, Long::sum);
        }
        long bySourceSum = 0;
        for (SourceCount row : bySource.rows()) {
            assertThat(row.count()).isEqualTo(expectedBySource.get(row.source()));
            bySourceSum += row.count();
        }
        assertThat(bySource.rows()).hasSize(expectedBySource.size());
        assertThat(bySource.total()).isEqualTo(inRange);
        assertThat(bySourceSum).isEqualTo(inRange);

        // --- conversion (Req 6.2): rate = won/leads, group leads sum to in-range. ---
        ConversionReport conversion = AGG.conversion(leads, WINDOW);
        assertConversionGrouping(conversion.bySource(), inRange);
        assertConversionGrouping(conversion.byOwner(), inRange);

        // --- pipeline (Req 6.3): active-stage counts equal the multiset grouping. ---
        PipelineReport pipeline = AGG.pipeline(leads);
        Map<LeadStatus, Long> expectedPipeline = new LinkedHashMap<>();
        long activeTotal = 0;
        for (LeadReportRecord l : leads) {
            if (ACTIVE.contains(l.status())) {
                expectedPipeline.merge(l.status(), 1L, Long::sum);
                activeTotal++;
            }
        }
        long pipelineSum = 0;
        for (PipelineCount row : pipeline.rows()) {
            assertThat(row.count()).isEqualTo(expectedPipeline.getOrDefault(row.status(), 0L));
            pipelineSum += row.count();
        }
        assertThat(pipeline.rows()).hasSize(ACTIVE.size());
        assertThat(pipeline.total()).isEqualTo(activeTotal);
        assertThat(pipelineSum).isEqualTo(activeTotal);

        // --- lost-reasons (Req 6.4): counts equal the in-range LOST grouping. ---
        LostReasonReport lost = AGG.lostReasons(leads, WINDOW);
        Map<LostReason, Long> expectedLost = new LinkedHashMap<>();
        long lostTotal = 0;
        for (LeadReportRecord l : within) {
            if (l.status() == LeadStatus.LOST && l.lostReason() != null) {
                expectedLost.merge(l.lostReason(), 1L, Long::sum);
                lostTotal++;
            }
        }
        long lostSum = 0;
        for (LostReasonCount row : lost.rows()) {
            assertThat(row.count()).isEqualTo(expectedLost.get(row.reason()));
            lostSum += row.count();
        }
        assertThat(lost.rows()).hasSize(expectedLost.size());
        assertThat(lost.total()).isEqualTo(lostTotal);
        assertThat(lostSum).isEqualTo(lostTotal);
    }

    /** Asserts a conversion grouping's rate = won/leads and leads sum to the in-range count. */
    private static void assertConversionGrouping(List<ConversionRow> rows, long inRange) {
        long leadsSum = 0;
        for (ConversionRow row : rows) {
            assertThat(row.won()).isLessThanOrEqualTo(row.leads());
            assertThat(row.conversionRate())
                    .isEqualByComparingTo(LeadReportAggregator.conversionRate(row.won(), row.leads()));
            leadsSum += row.leads();
        }
        assertThat(leadsSum).isEqualTo(inRange);
    }

    @Provide
    Arbitrary<List<LeadReportRecord>> leads() {
        return record().list().ofMaxSize(60);
    }

    private Arbitrary<LeadReportRecord> record() {
        Arbitrary<LeadSource> sources = Arbitraries.of(LeadSource.values());
        Arbitrary<LeadStatus> statuses = Arbitraries.of(LeadStatus.values());
        Arbitrary<LostReason> reasons = Arbitraries.of(LostReason.values()).injectNull(0.2);
        Arbitrary<Long> owners = Arbitraries.longs().between(1L, 4L).injectNull(0.1);
        // Dates spanning before / inside / after the window to exercise windowing.
        Arbitrary<LocalDate> dates = Arbitraries.integers().between(0, 270)
                .map(d -> LocalDate.parse("2024-01-01").plusDays(d))
                .injectNull(0.05);
        return Combinators.combine(sources, statuses, reasons, owners, dates)
                .as(LeadReportRecord::new);
    }
}
