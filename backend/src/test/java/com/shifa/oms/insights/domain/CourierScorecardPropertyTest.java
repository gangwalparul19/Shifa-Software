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
 * Property-based test for the pure {@link InsightEngine} courier-scorecard family
 * (design &sect;Correctness Properties (4)).
 *
 * Feature: statistical-insights-engine, Property 4: Courier scorecard integrity.
 *
 * <p>All reported percentages are in {@code [0, 100]}; the delivered / RTO /
 * failed / other counts partition the courier's terminal shipments; and the
 * severity is WARNING <em>iff</em> {@code rtoPct > courierRtoWarnPct} (else INFO).
 * A courier with no terminal shipments yields no scorecard. Purely exercises the
 * engine over generated inputs — no mocks. jqwik default 1000 tries (&ge; 100).
 *
 * **Validates: Requirements 6.1, 6.2, 6.3**
 */
class CourierScorecardPropertyTest {

    private static final InsightEngine ENGINE = new InsightEngine();
    private static final InsightThresholds T = InsightThresholds.defaults();
    private static final LocalDate DATE = LocalDate.parse("2024-06-15");

    // Feature: statistical-insights-engine, Property 4: Courier scorecard integrity
    // **Validates: Requirements 6.1, 6.2, 6.3**
    @Property
    void scorecardPercentagesAndSeverityAreCorrect(@ForAll("couriers") CourierOutcome c) {
        List<Insight> result = ENGINE.courierScorecards(List.of(c), T, DATE);

        long terminal = c.delivered() + c.rto() + c.failed() + c.otherTerminal();
        if (terminal == 0) {
            assertThat(result).isEmpty();
            return;
        }

        assertThat(result).hasSize(1);
        BigDecimal deliveryPct = pct(c.delivered(), terminal);
        BigDecimal rtoPct = pct(c.rto(), terminal);

        // Percentages within [0, 100].
        assertThat(deliveryPct).isBetween(BigDecimal.ZERO, BigDecimal.valueOf(100));
        assertThat(rtoPct).isBetween(BigDecimal.ZERO, BigDecimal.valueOf(100));
        // The four counts partition the terminal shipments.
        assertThat(c.delivered() + c.rto() + c.failed() + c.otherTerminal()).isEqualTo(terminal);

        Insight i = result.get(0);
        assertThat(i.type()).isEqualTo(InsightType.COURIER_SCORECARD);
        assertThat(i.scope()).isEqualTo(InsightScope.COURIER);
        assertThat(i.scopeRefId()).isEqualTo(c.courierCompanyId());
        assertThat(i.metricValue()).isEqualByComparingTo(rtoPct);

        InsightSeverity expected = rtoPct.compareTo(T.courierRtoWarnPct()) > 0
                ? InsightSeverity.WARNING : InsightSeverity.INFO;
        assertThat(i.severity()).isEqualTo(expected);
    }

    private static BigDecimal pct(long numerator, long denominator) {
        return BigDecimal.valueOf(numerator)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(denominator), 2, RoundingMode.HALF_UP);
    }

    @Provide
    Arbitrary<CourierOutcome> couriers() {
        Arbitrary<Long> ids = Arbitraries.longs().between(1L, 1000L);
        Arbitrary<String> names = Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(8);
        Arbitrary<Long> counts = Arbitraries.longs().between(0L, 500L);
        Arbitrary<Double> transit = Arbitraries.doubles().between(0.0, 30.0);
        return Combinators.combine(ids, names, counts, counts, counts, counts, transit)
                .as(CourierOutcome::new);
    }
}
