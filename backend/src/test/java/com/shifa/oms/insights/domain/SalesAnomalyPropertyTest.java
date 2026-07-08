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
 * Property-based test for the pure {@link InsightEngine} sales-anomaly family
 * (design &sect;Correctness Properties (1), &sect;Scoring detail).
 *
 * Feature: statistical-insights-engine, Property 1: Sales anomaly iff over threshold.
 *
 * <p>For any current/previous window totals: a {@code SALES_ANOMALY} is produced
 * <em>iff</em> {@code |pctChange| > salesAnomalyPct} (with {@code previous = 0 &amp;&amp;
 * current > 0} treated as a spike), the reported metric equals the exact
 * percentage change, and the direction (dip/spike) matches the sign. Purely
 * exercises the engine over generated inputs — no mocks. jqwik default 1000 tries
 * (&ge; 100).
 *
 * **Validates: Requirements 3.1, 3.2, 3.3**
 */
class SalesAnomalyPropertyTest {

    private static final InsightEngine ENGINE = new InsightEngine();
    private static final InsightThresholds T = InsightThresholds.defaults();
    private static final LocalDate DATE = LocalDate.parse("2024-06-15");

    // Feature: statistical-insights-engine, Property 1: Sales anomaly iff over threshold
    // **Validates: Requirements 3.1, 3.2, 3.3**
    @Property
    void anomalyProducedIffOverThreshold(@ForAll("amounts") BigDecimal current,
                                         @ForAll("amounts") BigDecimal previous) {
        List<Insight> result = ENGINE.salesAnomaly(new SalesWindow(current, previous), T, DATE);

        boolean spikeFromZero = previous.signum() == 0 && current.signum() > 0;
        BigDecimal pctChange = null;
        boolean expectedProduced;
        if (spikeFromZero) {
            expectedProduced = true;
        } else if (previous.signum() == 0) {
            expectedProduced = false;               // no prior and no current sales
        } else {
            pctChange = current.subtract(previous)
                    .multiply(BigDecimal.valueOf(100))
                    .divide(previous, 2, RoundingMode.HALF_UP);
            expectedProduced = pctChange.abs().compareTo(T.salesAnomalyPct()) > 0;
        }

        assertThat(result).hasSize(expectedProduced ? 1 : 0);
        if (!expectedProduced) {
            return;
        }

        Insight i = result.get(0);
        assertThat(i.type()).isEqualTo(InsightType.SALES_ANOMALY);
        assertThat(i.scope()).isEqualTo(InsightScope.GLOBAL);
        assertThat(i.scopeRefId()).isEqualTo(InsightEngine.GLOBAL_REF);
        assertThat(i.computedDate()).isEqualTo(DATE);

        if (spikeFromZero) {
            // spike from zero: INFO, no finite percentage to report
            assertThat(i.severity()).isEqualTo(InsightSeverity.INFO);
            assertThat(i.metricValue()).isNull();
        } else {
            assertThat(i.metricValue()).isEqualByComparingTo(pctChange);
            boolean dip = pctChange.signum() < 0;                 // direction matches sign
            if (dip) {
                BigDecimal twice = T.salesAnomalyPct().multiply(BigDecimal.valueOf(2));
                InsightSeverity expected = pctChange.abs().compareTo(twice) > 0
                        ? InsightSeverity.DANGER : InsightSeverity.WARNING;
                assertThat(i.severity()).isEqualTo(expected);
            } else {
                assertThat(i.severity()).isEqualTo(InsightSeverity.INFO);   // spike
            }
        }
    }

    @Provide
    Arbitrary<BigDecimal> amounts() {
        return Arbitraries.integers().between(0, 100_000).map(BigDecimal::valueOf);
    }
}
