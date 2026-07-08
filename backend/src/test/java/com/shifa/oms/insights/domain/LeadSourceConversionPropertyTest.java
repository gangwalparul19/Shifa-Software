package com.shifa.oms.insights.domain;

import com.shifa.oms.lead.LeadReportAggregator;
import com.shifa.oms.order.LeadSource;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based test for the pure {@link InsightEngine} lead-source conversion
 * family (design &sect;Correctness Properties (6)).
 *
 * Feature: statistical-insights-engine, Property 6: Lead-source conversion.
 *
 * <p>The best/worst channels are picked by the exact {@code won / leads} rate
 * ({@link LeadReportAggregator#conversionRate(long, long)}) with a deterministic
 * tie-break by source name; no insight is produced when there are no lead sources
 * or every source has zero leads; and the reported metric is the best rate. The
 * expected selection is restated independently here so the engine cannot silently
 * drift. Purely exercises the engine over generated inputs — no mocks. jqwik
 * default 1000 tries (&ge; 100).
 *
 * **Validates: Requirements 8.1, 8.2**
 */
class LeadSourceConversionPropertyTest {

    private static final InsightEngine ENGINE = new InsightEngine();
    private static final InsightThresholds T = InsightThresholds.defaults();
    private static final LocalDate DATE = LocalDate.parse("2024-06-15");

    // Feature: statistical-insights-engine, Property 6: Lead-source conversion
    // **Validates: Requirements 8.1, 8.2**
    @Property
    void bestAndWorstPickedByExactRate(@ForAll("channels") List<LeadSourceConversion> channels) {
        List<Insight> result = ENGINE.leadSourceConversion(channels, T, DATE);

        boolean anyLeads = channels.stream().anyMatch(c -> c.leads() > 0);
        if (channels.isEmpty() || !anyLeads) {
            assertThat(result).isEmpty();               // no leads to compare (Req 8.2)
            return;
        }

        // Independently restated best/worst selection (max/min rate, tie → lower name).
        LeadSourceConversion best = null;
        LeadSourceConversion worst = null;
        BigDecimal bestRate = null;
        BigDecimal worstRate = null;
        for (LeadSourceConversion c : channels) {
            BigDecimal rate = LeadReportAggregator.conversionRate(c.won(), c.leads());
            if (best == null || outranks(rate, c, bestRate, best, true)) {
                best = c;
                bestRate = rate;
            }
            if (worst == null || outranks(rate, c, worstRate, worst, false)) {
                worst = c;
                worstRate = rate;
            }
        }

        assertThat(result).hasSize(1);
        Insight i = result.get(0);
        assertThat(i.type()).isEqualTo(InsightType.LEAD_SOURCE_CONVERSION);
        assertThat(i.scope()).isEqualTo(InsightScope.GLOBAL);
        assertThat(i.scopeRefId()).isEqualTo(InsightEngine.GLOBAL_REF);
        assertThat(i.severity()).isEqualTo(InsightSeverity.INFO);
        assertThat(i.metricValue()).isEqualByComparingTo(bestRate);
        // The title names both extremes.
        assertThat(i.title()).contains(best.source().name()).contains(worst.source().name());
    }

    /**
     * Whether {@code (rate, candidate)} out-ranks the current pick: for
     * {@code wantMax} a higher rate wins, else a lower rate; ties break to the
     * lower source name (matching {@link InsightEngine}).
     */
    private static boolean outranks(BigDecimal rate, LeadSourceConversion candidate,
                                    BigDecimal pickRate, LeadSourceConversion pick, boolean wantMax) {
        int cmp = rate.compareTo(pickRate);
        if (cmp != 0) {
            return wantMax ? cmp > 0 : cmp < 0;
        }
        return candidate.source().name().compareTo(pick.source().name()) < 0;
    }

    @Provide
    Arbitrary<List<LeadSourceConversion>> channels() {
        return channel().list().ofMaxSize(8);
    }

    private Arbitrary<LeadSourceConversion> channel() {
        Arbitrary<LeadSource> sources = Arbitraries.of(LeadSource.values());
        Arbitrary<Long> leads = Arbitraries.longs().between(0L, 200L);
        return Combinators.combine(sources, leads).flatAs((source, leadCount) ->
                Arbitraries.longs().between(0L, Math.max(0L, leadCount))
                        .map(won -> new LeadSourceConversion(source, leadCount, won)));
    }
}
