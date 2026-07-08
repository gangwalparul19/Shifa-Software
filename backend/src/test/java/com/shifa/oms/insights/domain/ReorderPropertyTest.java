package com.shifa.oms.insights.domain;

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
 * Property-based test for the pure {@link InsightEngine} reorder family (design
 * &sect;Correctness Properties (2), &sect;Reorder detail).
 *
 * Feature: statistical-insights-engine, Property 2: Reorder correctness.
 *
 * <p>For any product consumption: a {@code LOW_STOCK_REORDER} is produced
 * <em>iff</em> {@code avgDaily > 0 &amp;&amp; coverDays < reorderCoverDays}; when
 * flagged the suggested quantity is non-negative and
 * {@code onHand + suggestedQty >= ceil(avgDaily · reorderCoverDays)}; and a
 * product with no consumption ({@code avgDaily == 0}) is never flagged. Purely
 * exercises the engine over generated inputs — no mocks. jqwik default 1000
 * tries (&ge; 100).
 *
 * **Validates: Requirements 4.1, 4.2, 4.3**
 */
class ReorderPropertyTest {

    private static final InsightEngine ENGINE = new InsightEngine();
    private static final InsightThresholds T = InsightThresholds.defaults();
    private static final LocalDate DATE = LocalDate.parse("2024-06-15");

    // Feature: statistical-insights-engine, Property 2: Reorder correctness
    // **Validates: Requirements 4.1, 4.2, 4.3**
    @Property
    void reorderFlaggedIffLowCover(@ForAll("products") ProductConsumption p) {
        List<Insight> result = ENGINE.reorder(List.of(p), T, DATE);

        double avgDaily = (double) p.unitsSoldInWindow() / p.lookbackDays();
        boolean expectedFlag;
        if (avgDaily <= 0) {
            expectedFlag = false;                       // no consumption — never flagged (Req 4.3)
        } else {
            double coverDays = p.onHand() / avgDaily;
            expectedFlag = coverDays < T.reorderCoverDays();
        }

        assertThat(result).hasSize(expectedFlag ? 1 : 0);
        if (!expectedFlag) {
            return;
        }

        long target = (long) Math.ceil(avgDaily * T.reorderCoverDays());
        long suggestedQty = Math.max(0L, target - p.onHand());

        Insight i = result.get(0);
        assertThat(i.type()).isEqualTo(InsightType.LOW_STOCK_REORDER);
        assertThat(i.scope()).isEqualTo(InsightScope.PRODUCT);
        assertThat(i.scopeRefId()).isEqualTo(p.productId());
        assertThat(i.metricValue()).isEqualByComparingTo(BigDecimal.valueOf(suggestedQty));

        // suggested quantity is non-negative and covers at least the target cover.
        assertThat(suggestedQty).isGreaterThanOrEqualTo(0L);
        assertThat(p.onHand() + suggestedQty).isGreaterThanOrEqualTo(target);
    }

    @Provide
    Arbitrary<ProductConsumption> products() {
        Arbitrary<Long> ids = Arbitraries.longs().between(1L, 1000L);
        Arbitrary<String> names = Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(8);
        Arbitrary<Integer> onHand = Arbitraries.integers().between(0, 500);
        Arbitrary<Long> sold = Arbitraries.longs().between(0L, 2000L);
        Arbitrary<Integer> lookback = Arbitraries.integers().between(1, 60);
        return Combinators.combine(ids, names, onHand, sold, lookback)
                .as(ProductConsumption::new);
    }
}
