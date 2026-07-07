package com.shifa.oms.dashboard;

import com.shifa.oms.reporting.domain.DateRange;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Example-based tests for {@link MetricsPeriod} window resolution (Req 19.2):
 * each preset maps to the expected inclusive {@link DateRange} relative to a
 * fixed reference date, and lenient parsing accepts common spellings.
 */
class MetricsPeriodTest {

    // A Wednesday in mid-month/mid-quarter so week/month/quarter edges are clear.
    private static final LocalDate TODAY = LocalDate.of(2024, 5, 15);

    @Test
    void todayResolvesToSingleDay() {
        assertThat(MetricsPeriod.TODAY.resolve(TODAY, null, null))
                .isEqualTo(new DateRange(TODAY, TODAY));
    }

    @Test
    void yesterdayResolvesToPriorDay() {
        LocalDate y = LocalDate.of(2024, 5, 14);
        assertThat(MetricsPeriod.YESTERDAY.resolve(TODAY, null, null))
                .isEqualTo(new DateRange(y, y));
    }

    @Test
    void last7DaysIsInclusiveSevenDayWindow() {
        assertThat(MetricsPeriod.LAST_7_DAYS.resolve(TODAY, null, null))
                .isEqualTo(new DateRange(LocalDate.of(2024, 5, 9), TODAY));
    }

    @Test
    void last30DaysIsInclusiveThirtyDayWindow() {
        assertThat(MetricsPeriod.LAST_30_DAYS.resolve(TODAY, null, null))
                .isEqualTo(new DateRange(LocalDate.of(2024, 4, 16), TODAY));
    }

    @Test
    void thisMonthStartsAtFirstOfMonth() {
        assertThat(MetricsPeriod.THIS_MONTH.resolve(TODAY, null, null))
                .isEqualTo(new DateRange(LocalDate.of(2024, 5, 1), TODAY));
    }

    @Test
    void lastMonthIsWholePriorCalendarMonth() {
        assertThat(MetricsPeriod.LAST_MONTH.resolve(TODAY, null, null))
                .isEqualTo(new DateRange(LocalDate.of(2024, 4, 1), LocalDate.of(2024, 4, 30)));
    }

    @Test
    void quarterlyStartsAtFirstDayOfCurrentQuarter() {
        // May is in Q2 (Apr-Jun).
        assertThat(MetricsPeriod.QUARTERLY.resolve(TODAY, null, null))
                .isEqualTo(new DateRange(LocalDate.of(2024, 4, 1), TODAY));
    }

    @Test
    void yearlyStartsAtFirstDayOfYear() {
        assertThat(MetricsPeriod.YEARLY.resolve(TODAY, null, null))
                .isEqualTo(new DateRange(LocalDate.of(2024, 1, 1), TODAY));
    }

    @Test
    void customUsesSuppliedBounds() {
        LocalDate from = LocalDate.of(2024, 3, 1);
        LocalDate to = LocalDate.of(2024, 3, 31);
        assertThat(MetricsPeriod.CUSTOM.resolve(TODAY, from, to))
                .isEqualTo(new DateRange(from, to));
    }

    @Test
    void parsingIsLenientAndDefaultsWhenBlank() {
        assertThat(MetricsPeriod.from(null)).isEqualTo(MetricsPeriod.DEFAULT);
        assertThat(MetricsPeriod.from("  ")).isEqualTo(MetricsPeriod.DEFAULT);
        assertThat(MetricsPeriod.from("last-7-days")).isEqualTo(MetricsPeriod.LAST_7_DAYS);
        assertThat(MetricsPeriod.from("THIS MONTH")).isEqualTo(MetricsPeriod.THIS_MONTH);
        assertThat(MetricsPeriod.from("yearly")).isEqualTo(MetricsPeriod.YEARLY);
    }

    @Test
    void previousPeriodIsEqualLengthPrecedingWindow() {
        DateRange last7 = MetricsPeriod.LAST_7_DAYS.resolve(TODAY, null, null);
        DateRange previous = last7.previousPeriod();
        assertThat(previous).isEqualTo(
                new DateRange(LocalDate.of(2024, 5, 2), LocalDate.of(2024, 5, 8)));
    }
}
