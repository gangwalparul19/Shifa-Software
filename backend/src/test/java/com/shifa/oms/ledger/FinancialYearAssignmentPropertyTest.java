// © Shifa OMS
package com.shifa.oms.ledger;

import com.shifa.oms.ledger.FinancialYearService.FinancialYearWindow;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;

import java.time.LocalDate;
import java.time.Month;
import java.time.YearMonth;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based test for {@link FinancialYearService#windowFor(LocalDate)} (General Ledger, design
 * Correctness Property 5).
 *
 * <p>Feature: general-ledger-accounting, Property 5: Financial-year assignment contains the voucher
 * date.
 *
 * <p><b>Validates: Requirements 4.1, 4.2</b>
 *
 * <p>Targets the pure static {@link FinancialYearService#windowFor(LocalDate)} — the Indian
 * financial-year (1 April – 31 March) window resolver — so the property is verified with no Spring
 * context or database.
 */
class FinancialYearAssignmentPropertyTest {

    // ---------------------------------------------------------------------------------------------
    // Feature: general-ledger-accounting, Property 5: Financial-year assignment contains the voucher date
    // **Validates: Requirements 4.1, 4.2**
    // For any voucher date, the resolved financial year is the Indian FY (1 April – 31 March) whose
    // start and end dates contain the date (start <= date <= end); the start is 1 April, the end is
    // 31 March of the following year, and the label reads like "2025-26".
    // ---------------------------------------------------------------------------------------------

    /** The resolved window's start and end always contain the voucher date (start <= date <= end). */
    @Property(tries = 300)
    void windowContainsTheVoucherDate(@ForAll("voucherDates") LocalDate date) {
        FinancialYearWindow window = FinancialYearService.windowFor(date);

        assertThat(window.startDate()).isBeforeOrEqualTo(date);
        assertThat(window.endDate()).isAfterOrEqualTo(date);
    }

    /** The start is always 1 April and the end is always 31 March of the very next calendar year. */
    @Property(tries = 300)
    void windowSpansAprilFirstToNextMarchThirtyFirst(@ForAll("voucherDates") LocalDate date) {
        FinancialYearWindow window = FinancialYearService.windowFor(date);

        assertThat(window.startDate().getMonth()).isEqualTo(Month.APRIL);
        assertThat(window.startDate().getDayOfMonth()).isEqualTo(1);

        assertThat(window.endDate().getMonth()).isEqualTo(Month.MARCH);
        assertThat(window.endDate().getDayOfMonth()).isEqualTo(31);

        // The Indian FY is exactly one calendar year: 1 Apr YYYY -> 31 Mar (YYYY+1).
        assertThat(window.endDate().getYear()).isEqualTo(window.startDate().getYear() + 1);
    }

    /** The start year is the date's own year from April onward, else the previous calendar year. */
    @Property(tries = 300)
    void startYearIsChosenByTheAprilBoundary(@ForAll("voucherDates") LocalDate date) {
        FinancialYearWindow window = FinancialYearService.windowFor(date);

        int expectedStartYear = date.getMonthValue() >= 4 ? date.getYear() : date.getYear() - 1;
        assertThat(window.startDate().getYear()).isEqualTo(expectedStartYear);
    }

    /** The label reads like "2025-26": start year, a dash, then the two-digit following year. */
    @Property(tries = 300)
    void labelHasTheYearOverYearFormat(@ForAll("voucherDates") LocalDate date) {
        FinancialYearWindow window = FinancialYearService.windowFor(date);

        int startYear = window.startDate().getYear();
        String expectedLabel = String.format("%d-%02d", startYear, (startYear + 1) % 100);
        assertThat(window.label()).isEqualTo(expectedLabel);
    }

    // --- Generators ------------------------------------------------------------------------------

    /** Arbitrary valid dates spread across all months/days over a wide year range (1970–2100). */
    @Provide
    Arbitrary<LocalDate> voucherDates() {
        Arbitrary<Integer> years = net.jqwik.api.Arbitraries.integers().between(1970, 2100);
        Arbitrary<Integer> months = net.jqwik.api.Arbitraries.integers().between(1, 12);
        Arbitrary<Integer> days = net.jqwik.api.Arbitraries.integers().between(1, 31);
        return Combinators.combine(years, months, days).as((year, month, day) -> {
            int lastDay = YearMonth.of(year, month).lengthOfMonth();
            return LocalDate.of(year, month, Math.min(day, lastDay));
        });
    }

    /** Exercises the exact FY boundary days directly (1 Apr and 31 Mar) across the range. */
    @Property(tries = 200)
    void boundaryDaysResolveToTheYearTheyBelongTo(@ForAll @IntRange(min = 1970, max = 2100) int year) {
        LocalDate aprilFirst = LocalDate.of(year, 4, 1);
        LocalDate marchThirtyFirst = LocalDate.of(year, 3, 31);

        FinancialYearWindow forApril = FinancialYearService.windowFor(aprilFirst);
        assertThat(forApril.startDate()).isEqualTo(aprilFirst);
        assertThat(forApril.startDate()).isBeforeOrEqualTo(aprilFirst);
        assertThat(forApril.endDate()).isAfterOrEqualTo(aprilFirst);

        FinancialYearWindow forMarch = FinancialYearService.windowFor(marchThirtyFirst);
        assertThat(forMarch.endDate()).isEqualTo(marchThirtyFirst);
        assertThat(forMarch.startDate()).isBeforeOrEqualTo(marchThirtyFirst);
        assertThat(forMarch.endDate()).isAfterOrEqualTo(marchThirtyFirst);
    }
}
