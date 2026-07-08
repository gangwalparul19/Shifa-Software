package com.shifa.oms.insights.domain;

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
 * Property-based test for the determinism / idempotency of the pure
 * {@link InsightEngine} (design &sect;Correctness Properties (7)).
 *
 * Feature: statistical-insights-engine, Property 7: Determinism / idempotency.
 *
 * <p>{@code compute(inputs, thresholds, date)} called twice on equal inputs
 * yields an equal {@link Insight} list — same size, same order, same natural
 * keys — which is what underpins idempotent persistence (a same-date recompute
 * replaces with an identical set). Purely exercises the engine over generated
 * inputs — no mocks. jqwik default 1000 tries (&ge; 100).
 *
 * **Validates: Requirement 1.2**
 */
class DeterminismPropertyTest {

    private static final InsightEngine ENGINE = new InsightEngine();
    private static final InsightThresholds T = InsightThresholds.defaults();
    private static final LocalDate DATE = LocalDate.parse("2024-06-15");

    // Feature: statistical-insights-engine, Property 7: Determinism / idempotency
    // **Validates: Requirement 1.2**
    @Property
    void computeIsDeterministic(@ForAll("inputs") InsightInputs inputs) {
        List<Insight> first = ENGINE.compute(inputs, T, DATE);
        List<Insight> second = ENGINE.compute(inputs, T, DATE);

        // Equal lists (records ⇒ structural equality): same size, order, and content.
        assertThat(second).isEqualTo(first);
        // The natural keys line up positionally too.
        assertThat(second.stream().map(Insight::naturalKey).toList())
                .isEqualTo(first.stream().map(Insight::naturalKey).toList());
    }

    @Provide
    Arbitrary<InsightInputs> inputs() {
        return Combinators.combine(
                salesWindow(), products(), couriers(), openOrders(), returnStats(), cod(), leadSources())
                .as(InsightInputs::new);
    }

    private Arbitrary<SalesWindow> salesWindow() {
        Arbitrary<BigDecimal> amount = Arbitraries.integers().between(0, 100_000).map(BigDecimal::valueOf);
        return Combinators.combine(amount, amount).as(SalesWindow::new).injectNull(0.1);
    }

    private Arbitrary<List<ProductConsumption>> products() {
        Arbitrary<ProductConsumption> one = Combinators.combine(
                        Arbitraries.longs().between(1L, 1000L),
                        Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(8),
                        Arbitraries.integers().between(0, 500),
                        Arbitraries.longs().between(0L, 2000L),
                        Arbitraries.integers().between(1, 60))
                .as(ProductConsumption::new);
        return one.list().ofMaxSize(6).injectNull(0.1);
    }

    private Arbitrary<List<CourierOutcome>> couriers() {
        Arbitrary<Long> counts = Arbitraries.longs().between(0L, 500L);
        Arbitrary<CourierOutcome> one = Combinators.combine(
                        Arbitraries.longs().between(1L, 1000L),
                        Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(8),
                        counts, counts, counts, counts,
                        Arbitraries.doubles().between(0.0, 30.0))
                .as(CourierOutcome::new);
        return one.list().ofMaxSize(6).injectNull(0.1);
    }

    private Arbitrary<List<OpenOrderRisk>> openOrders() {
        Arbitrary<OpenOrderRisk> one = Combinators.combine(
                        Arbitraries.longs().between(1L, 1000L),
                        Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(6),
                        Arbitraries.integers().between(0, 10_000).map(BigDecimal::valueOf).injectNull(0.1),
                        Arbitraries.of("MH", "DL", "KA", "UP", "TN"),
                        Arbitraries.integers().between(0, 10),
                        Arbitraries.doubles().between(0.0, 1.0))
                .as(OpenOrderRisk::new);
        return one.list().ofMaxSize(6).injectNull(0.1);
    }

    private Arbitrary<ReturnStats> returnStats() {
        Arbitrary<Long> counts = Arbitraries.longs().between(0L, 5000L);
        return Combinators.combine(counts, counts).as(ReturnStats::new).injectNull(0.1);
    }

    private Arbitrary<CodOutstanding> cod() {
        return Arbitraries.integers().between(0, 200_000)
                .map(v -> new CodOutstanding(BigDecimal.valueOf(v))).injectNull(0.1);
    }

    private Arbitrary<List<LeadSourceConversion>> leadSources() {
        Arbitrary<LeadSourceConversion> one = Combinators.combine(
                        Arbitraries.of(LeadSource.values()),
                        Arbitraries.longs().between(0L, 200L))
                .flatAs((source, leads) -> Arbitraries.longs().between(0L, Math.max(0L, leads))
                        .map(won -> new LeadSourceConversion(source, leads, won)));
        return one.list().ofMaxSize(6).injectNull(0.1);
    }
}
