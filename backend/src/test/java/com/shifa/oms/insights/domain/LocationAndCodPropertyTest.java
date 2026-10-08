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
 * Property-based test for the pure {@link InsightEngine} location families
 * ({@code UNDERPERFORMING_LOCATION}) and the COD-outstanding family.
 *
 * <p>An {@code UNDERPERFORMING_LOCATION} is produced for a state <em>iff</em> it
 * has at least {@code locationMinOrders} orders, at least one concluded delivery,
 * and its failure share {@code failed / (delivered + failed) · 100} exceeds
 * {@code locationFailWarnPct}. A {@code COD_OUTSTANDING_BUILDUP} is produced
 * <em>iff</em> {@code unsettled > codOutstandingWarn}. Purely exercises the
 * engine over generated inputs — no mocks.
 */
class LocationAndCodPropertyTest {

    private static final InsightEngine ENGINE = new InsightEngine();
    private static final InsightThresholds T = InsightThresholds.defaults();
    private static final LocalDate DATE = LocalDate.parse("2024-06-15");

    @Property
    void underperformingLocationFlaggedIffHighFailureWithEnoughVolume(
            @ForAll("counts") long delivered,
            @ForAll("counts") long failed,
            @ForAll("extraOrders") long extraOrders) {
        long concluded = delivered + failed;
        // Total orders includes the concluded ones plus some still-open ones.
        long orders = concluded + extraOrders;
        LocationPerformance loc = new LocationPerformance(
                "Maharashtra", orders, BigDecimal.valueOf(orders * 100L), delivered, failed);

        List<Insight> result = ENGINE.underperformingLocations(List.of(loc), T, DATE);

        boolean enoughVolume = orders >= T.locationMinOrders() && concluded > 0;
        if (!enoughVolume) {
            assertThat(result).isEmpty();
            return;
        }
        BigDecimal failPct = BigDecimal.valueOf(failed)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(concluded), 2, RoundingMode.HALF_UP);
        boolean expectedFlag = failPct.compareTo(T.locationFailWarnPct()) > 0;
        assertThat(result).hasSize(expectedFlag ? 1 : 0);
        if (!expectedFlag) {
            return;
        }
        Insight i = result.get(0);
        assertThat(i.type()).isEqualTo(InsightType.UNDERPERFORMING_LOCATION);
        assertThat(i.scope()).isEqualTo(InsightScope.GLOBAL);
        assertThat(i.metricValue()).isEqualByComparingTo(failPct);
        BigDecimal twice = T.locationFailWarnPct().multiply(BigDecimal.valueOf(2));
        InsightSeverity expected = failPct.compareTo(twice) > 0
                ? InsightSeverity.DANGER : InsightSeverity.WARNING;
        assertThat(i.severity()).isEqualTo(expected);
    }

    @Property
    void topSalesLocationPicksHighestRevenueStateWithEnoughOrders(
            @ForAll("revenue") long revA,
            @ForAll("revenue") long revB) {
        long minOrders = T.locationMinOrders();
        LocationPerformance a = new LocationPerformance(
                "Alpha", minOrders, BigDecimal.valueOf(revA), minOrders, 0);
        LocationPerformance b = new LocationPerformance(
                "Bravo", minOrders, BigDecimal.valueOf(revB), minOrders, 0);

        List<Insight> result = ENGINE.topSalesLocation(List.of(a, b), T, DATE);

        if (revA <= 0 && revB <= 0) {
            assertThat(result).isEmpty();               // no positive revenue anywhere
            return;
        }
        assertThat(result).hasSize(1);
        Insight i = result.get(0);
        assertThat(i.type()).isEqualTo(InsightType.TOP_SALES_LOCATION);
        assertThat(i.severity()).isEqualTo(InsightSeverity.INFO);
        // Winner is the higher revenue (tie-break by name → "Alpha").
        String winner = revA >= revB ? "Alpha" : "Bravo";
        assertThat(i.scopeLabel()).isEqualTo(winner);
    }

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
        return Arbitraries.longs().between(0L, 500L);
    }

    @Provide
    Arbitrary<Long> extraOrders() {
        return Arbitraries.longs().between(0L, 50L);
    }

    @Provide
    Arbitrary<Long> revenue() {
        return Arbitraries.longs().between(0L, 1_000_000L);
    }

    @Provide
    Arbitrary<BigDecimal> cods() {
        return Arbitraries.integers().between(0, 200_000).map(BigDecimal::valueOf);
    }
}
