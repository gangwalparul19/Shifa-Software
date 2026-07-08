package com.shifa.oms.insights.domain;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based test for the pure {@link InsightEngine} RTO-risk family (design
 * &sect;Correctness Properties (3), &sect;Scoring detail).
 *
 * Feature: statistical-insights-engine, Property 3: RTO score bounds &amp; monotonicity.
 *
 * <p>The score is always in {@code [0, 100]}; increasing any single risk factor
 * (COD amount, destination-state failure rate, prior failed attempts) never
 * decreases it; and an {@code RTO_RISK} is produced <em>iff</em>
 * {@code score >= rtoRiskThreshold}. (Terminal/delivered orders are excluded by
 * the caller — the engine scores only what it is handed.) Purely exercises the
 * engine over generated inputs — no mocks. jqwik default 1000 tries (&ge; 100).
 *
 * **Validates: Requirements 5.1, 5.2, 5.3**
 */
class RtoRiskPropertyTest {

    private static final InsightEngine ENGINE = new InsightEngine();
    private static final InsightThresholds T = InsightThresholds.defaults();
    private static final LocalDate DATE = LocalDate.parse("2024-06-15");

    // Feature: statistical-insights-engine, Property 3: RTO score bounds & threshold
    // **Validates: Requirements 5.1, 5.2, 5.3**
    @Property
    void scoreWithinBoundsAndFlaggedIffOverThreshold(@ForAll("orders") OpenOrderRisk order) {
        double score = ENGINE.rtoRiskScore(order);
        assertThat(score).isBetween(0.0, 100.0);

        List<Insight> result = ENGINE.rtoRisk(List.of(order), T, DATE);
        boolean expectedFlag = score >= T.rtoRiskThreshold();
        assertThat(result).hasSize(expectedFlag ? 1 : 0);
        if (!expectedFlag) {
            return;
        }

        Insight i = result.get(0);
        assertThat(i.type()).isEqualTo(InsightType.RTO_RISK);
        assertThat(i.scope()).isEqualTo(InsightScope.ORDER);
        assertThat(i.scopeRefId()).isEqualTo(order.orderId());
        BigDecimal metric = BigDecimal.valueOf(score).setScale(0, RoundingMode.HALF_UP);
        assertThat(i.metricValue()).isEqualByComparingTo(metric);
        InsightSeverity expected = score >= 80 ? InsightSeverity.DANGER : InsightSeverity.WARNING;
        assertThat(i.severity()).isEqualTo(expected);
    }

    // Feature: statistical-insights-engine, Property 3: RTO score monotonicity
    // **Validates: Requirement 5.2**
    @Property
    void scoreIsMonotonicInEveryFactor(@ForAll("states") double state,
                                       @ForAll("states") double stateDelta,
                                       @ForAll("cods") int cod,
                                       @ForAll("cods") int codDelta,
                                       @ForAll("priors") int prior,
                                       @ForAll("priors") int priorDelta) {
        OpenOrderRisk low = new OpenOrderRisk(
                1L, "O1", BigDecimal.valueOf(cod), "MH", prior, state);
        OpenOrderRisk high = new OpenOrderRisk(
                1L, "O1", BigDecimal.valueOf((long) cod + codDelta), "MH",
                prior + priorDelta, state + stateDelta);

        // Dominating every factor never decreases the score (hence each single
        // factor is monotone non-decreasing).
        assertThat(ENGINE.rtoRiskScore(high)).isGreaterThanOrEqualTo(ENGINE.rtoRiskScore(low));
    }

    @Provide
    Arbitrary<OpenOrderRisk> orders() {
        Arbitrary<Long> ids = Arbitraries.longs().between(1L, 1000L);
        Arbitrary<String> codes = Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(6);
        Arbitrary<BigDecimal> cods = Arbitraries.integers().between(0, 10_000)
                .map(BigDecimal::valueOf).injectNull(0.1);
        Arbitrary<String> stateNames = Arbitraries.of("MH", "DL", "KA", "UP", "TN");
        Arbitrary<Integer> priors = Arbitraries.integers().between(0, 10);
        Arbitrary<Double> rates = Arbitraries.doubles().between(0.0, 1.0);
        return Combinators.combine(ids, codes, cods, stateNames, priors, rates)
                .as(OpenOrderRisk::new);
    }

    @Provide
    Arbitrary<Double> states() {
        return Arbitraries.doubles().between(0.0, 1.0);
    }

    @Provide
    Arbitrary<Integer> cods() {
        return Arbitraries.integers().between(0, 10_000);
    }

    @Provide
    Arbitrary<Integer> priors() {
        return Arbitraries.integers().between(0, 10);
    }
}
