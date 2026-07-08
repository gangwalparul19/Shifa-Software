package com.shifa.oms.insights.domain;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based test for the pure {@link InsightEngine} return-rate and
 * COD-outstanding families (design &sect;Correctness Properties (5)).
 *
 * Feature: statistical-insights-engine, Property 5: Return-rate &amp; COD thresholds.
 *
 * <p>A {@code RETURN_RATE_ANOMALY} is produced <em>iff</em>
 * {@code rate > returnRateWarnPct} (rate is 0 and never flagged when nothing was
 * delivered); a {@code COD_OUTSTANDING_BUILDUP} is produced <em>iff</em>
 * {@code unsettled > codOutstandingWarn}. Purely exercises the engine over
 * generated inputs — no mocks. jqwik default 1000 tries (&ge; 100).
 *
 * **Validates: Requirements 7.1, 7.2**
 */
class ReturnRateAndCodPropertyTest {

    private static final InsightEngine ENGINE = new InsightEngine();
    private static final InsightThresholds T = InsightThresholds.defaults();
    private static final LocalDate DATE = LocalDate.parse("2024-06-15");

    // Feature: statistical-insights-engine, Property 5: Return-rate threshold
    // **Validates: Requirement 7.1**
    @Property
    void returnRateFlaggedIffOverThreshold(@ForAll("counts") long delivered,
                                           @ForAll("counts") long returns) {
        List<Insight> result = ENGINE.returnRate(new ReturnStats(delivered, returns), T, DATE);

        if (delivered <= 0) {
            assertThat(result).isEmpty();               // rate = 0, never flagged
            return;
        }
        BigDecimal rate = BigDecimal.valueOf(returns)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(delivered), 2, RoundingMode.HALF_UP);
        boolean expectedFlag = rate.compareTo(T.returnRateWarnPct()) > 0;
        assertThat(result).hasSize(expectedFlag ? 1 : 0);
        if (!expectedFlag) {
            return;
        }

        Insight i = result.get(0);
        assertThat(i.type()).isEqualTo(InsightType.RETURN_RATE_ANOMALY);
        assertThat(i.scope()).isEqualTo(InsightScope.GLOBAL);
        assertThat(i.scopeRefId()).isEqualTo(InsightEngine.GLOBAL_REF);
        assertThat(i.metricValue()).isEqualByComparingTo(rate);
        BigDecimal twice = T.returnRateWarnPct().multiply(BigDecimal.valueOf(2));
        InsightSeverity expected = rate.compareTo(twice) > 0
                ? InsightSeverity.DANGER : InsightSeverity.WARNING;
        assertThat(i.severity()).isEqualTo(expected);
    }

    // Feature: statistical-insights-engine, Property 5: COD-outstanding threshold
    // **Validates: Requirement 7.2**
    @Property
    void codBuildupFlaggedIffOverThreshold(@ForAll("cods") BigDecimal unsettled) {
        List<Insight> result = ENGINE.codBuildup(new CodOutstanding(unsettled), T, DATE);

        boolean expectedFlag = unsettled.compareTo(T.codOutstandingWarn()) > 0;
        assertThat(result).hasSize(expectedFlag ? 1 : 0);
        if (!expectedFlag) {
            return;
        }

        Insight i = result.get(0);
        assertThat(i.type()).isEqualTo(InsightType.COD_OUTSTANDING_BUILDUP);
        assertThat(i.scope()).isEqualTo(InsightScope.GLOBAL);
        assertThat(i.scopeRefId()).isEqualTo(InsightEngine.GLOBAL_REF);
        assertThat(i.severity()).isEqualTo(InsightSeverity.WARNING);
        assertThat(i.metricValue()).isEqualByComparingTo(unsettled);
    }

    @Provide
    Arbitrary<Long> counts() {
        return Arbitraries.longs().between(0L, 5_000L);
    }

    @Provide
    Arbitrary<BigDecimal> cods() {
        return Arbitraries.integers().between(0, 200_000).map(BigDecimal::valueOf);
    }
}
