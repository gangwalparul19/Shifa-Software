package com.shifa.oms.ledger;

import com.shifa.oms.ledger.DayBookService.DayBook;
import com.shifa.oms.ledger.DayBookService.DayBookRow;
import com.shifa.oms.ledger.domain.VoucherType;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Property-based test for {@link DayBookService#dayBook(Long, LocalDate, LocalDate, VoucherType)} —
 * the Day Book returns exactly the in-period vouchers in order (General Ledger, design Correctness
 * Property 19).
 *
 * <p>Feature: general-ledger-accounting, Property 19: The Day Book returns exactly the in-period
 * vouchers in order.
 *
 * <p>Property statement: for any set of vouchers and any reporting period, the Day Book returns
 * exactly the vouchers whose voucher date falls within the period, ordered chronologically (voucher
 * date then id); and when a voucher-type filter is supplied, it returns only vouchers of that type
 * (still in-period, still in order).
 *
 * <p><b>Validates: Requirements 13.1, 13.3</b>
 *
 * <p>This is a <em>service-level</em> property test that exercises the real
 * {@link DayBookService#dayBook(Long, LocalDate, LocalDate, VoucherType)}. Per the project's Java 25
 * gotcha (Mockito cannot mock concrete classes), only the Spring Data repository <em>interfaces</em>
 * ({@link VoucherRepository}, {@link VoucherLineRepository}) are Mockito-mocked; the period resolver
 * is a REAL {@link ReportPeriodResolver} over a REAL {@link FinancialYearService} built over those
 * mocked repositories (mirroring the sibling ledger property tests). The reporting period is always
 * supplied as an explicit {@code from}/{@code to} range so the resolver returns it directly without
 * touching the financial-year store.
 *
 * <p>The mocked repository finders are kept <em>faithful to their declared filtering/ordering
 * contract</em>: {@code findByVoucherDateBetweenOrderByVoucherDateAscIdAsc} returns the generated
 * population filtered to voucher dates within {@code [from, to]} and sorted by voucher date then id,
 * and the type-filtered variant additionally restricts to the requested type. The test's oracle
 * recomputes that same filtered/ordered set independently and asserts the service's Day Book rows
 * match it exactly — proving the service (a) selects the correct finder for the requested filter and
 * (b) returns the in-period vouchers, in chronological order, one row per voucher.
 */
class DayBookFilteringPropertyTest {

    /** A fixed anchor the generated period and voucher dates are spread around. */
    private static final LocalDate ANCHOR = LocalDate.of(2024, 6, 15);

    // ---------------------------------------------------------------------------------------------
    // Feature: general-ledger-accounting, Property 19: The Day Book returns exactly the in-period
    // vouchers in order
    // **Validates: Requirements 13.1, 13.3**
    // ---------------------------------------------------------------------------------------------

    /**
     * Req 13.1: with no voucher-type filter, the Day Book returns exactly the vouchers whose date
     * falls within the resolved period, ordered by voucher date then id, and nothing outside the
     * period.
     */
    @Property(tries = 200)
    void dayBookReturnsExactlyTheInPeriodVouchersInOrder(@ForAll("scenarios") Scenario scenario) {
        Fixture f = newFixture(scenario);

        DayBook dayBook = f.service.dayBook(null, scenario.from(), scenario.to(), null);

        List<Long> expected = oracle(scenario, null);
        List<Long> actual = idsOf(dayBook);

        assertThat(actual)
                .as("Day Book returns exactly the in-period vouchers, in chronological order")
                .isEqualTo(expected);
        assertThat(dayBook.rows())
                .as("every returned voucher's date falls within the reporting period")
                .allSatisfy(row -> assertThat(row.date())
                        .isAfterOrEqualTo(scenario.from())
                        .isBeforeOrEqualTo(scenario.to()));
        assertChronological(dayBook.rows());
    }

    /**
     * Req 13.3: when a voucher-type filter is supplied, the Day Book returns only vouchers of that
     * type, still restricted to the period and still ordered chronologically.
     */
    @Property(tries = 200)
    void dayBookWithTypeFilterReturnsOnlyThatTypeInPeriod(@ForAll("scenarios") Scenario scenario,
                                                          @ForAll("voucherTypes") VoucherType filter) {
        Fixture f = newFixture(scenario);

        DayBook dayBook = f.service.dayBook(null, scenario.from(), scenario.to(), filter);

        List<Long> expected = oracle(scenario, filter);
        List<Long> actual = idsOf(dayBook);

        assertThat(actual)
                .as("Day Book with a type filter returns exactly the in-period vouchers of that type, in order")
                .isEqualTo(expected);
        assertThat(dayBook.voucherType())
                .as("the applied voucher-type filter is echoed on the result")
                .isEqualTo(filter);
        assertThat(dayBook.rows())
                .as("every returned voucher is of the filtered type and within the period")
                .allSatisfy(row -> {
                    assertThat(row.type()).isEqualTo(filter);
                    assertThat(row.date()).isAfterOrEqualTo(scenario.from()).isBeforeOrEqualTo(scenario.to());
                });
        assertChronological(dayBook.rows());
    }

    // --- Oracle ----------------------------------------------------------------------------------

    /**
     * The independent oracle: the ids of the generated vouchers whose date falls within the period
     * (and match the type filter when one is supplied), sorted by voucher date then id.
     */
    private static List<Long> oracle(Scenario scenario, VoucherType filter) {
        return scenario.vouchers().stream()
                .filter(v -> !v.date().isBefore(scenario.from()) && !v.date().isAfter(scenario.to()))
                .filter(v -> filter == null || v.type() == filter)
                .sorted(Comparator.comparing(VoucherSpec::date).thenComparing(VoucherSpec::id))
                .map(VoucherSpec::id)
                .toList();
    }

    private static List<Long> idsOf(DayBook dayBook) {
        return dayBook.rows().stream().map(DayBookRow::voucherId).toList();
    }

    private static void assertChronological(List<DayBookRow> rows) {
        for (int i = 1; i < rows.size(); i++) {
            DayBookRow prev = rows.get(i - 1);
            DayBookRow curr = rows.get(i);
            assertThat(prev.date())
                    .as("rows are ordered by voucher date ascending")
                    .isBeforeOrEqualTo(curr.date());
            if (prev.date().isEqual(curr.date())) {
                assertThat(prev.voucherId())
                        .as("ties on voucher date are broken by ascending id")
                        .isLessThan(curr.voucherId());
            }
        }
    }

    // --- Generators ------------------------------------------------------------------------------

    @Provide
    Arbitrary<VoucherType> voucherTypes() {
        return Arbitraries.of(VoucherType.values());
    }

    @Provide
    Arbitrary<Scenario> scenarios() {
        Arbitrary<VoucherSpec> voucherSpec = Combinators.combine(
                        Arbitraries.of(VoucherType.values()),
                        Arbitraries.integers().between(-300, 600))
                .as((type, dayOffset) -> new VoucherSpec(0L, type, ANCHOR.plusDays(dayOffset)));
        Arbitrary<List<VoucherSpec>> vouchers = voucherSpec.list().ofMinSize(0).ofMaxSize(15);
        Arbitrary<Integer> fromOffset = Arbitraries.integers().between(-100, 100);
        Arbitrary<Integer> spanDays = Arbitraries.integers().between(0, 300);
        return Combinators.combine(vouchers, fromOffset, spanDays).as((specs, from, span) -> {
            // Assign each generated voucher a distinct, increasing id (1..n) in generation order.
            List<VoucherSpec> withIds = new ArrayList<>(specs.size());
            long id = 1L;
            for (VoucherSpec spec : specs) {
                withIds.add(new VoucherSpec(id++, spec.type(), spec.date()));
            }
            LocalDate periodFrom = ANCHOR.plusDays(from);
            LocalDate periodTo = periodFrom.plusDays(span);
            return new Scenario(withIds, periodFrom, periodTo);
        });
    }

    /** One generated voucher: an id (assigned in the scenario), a type, and a voucher date. */
    record VoucherSpec(Long id, VoucherType type, LocalDate date) {
    }

    /** A generated population of vouchers plus the inclusive reporting-period range. */
    record Scenario(List<VoucherSpec> vouchers, LocalDate from, LocalDate to) {
    }

    // --- Fixture: DayBookService over mocked repository interfaces + real collaborators -----------

    private record Fixture(DayBookService service) {
    }

    private static Fixture newFixture(Scenario scenario) {
        VoucherRepository voucherRepo = mock(VoucherRepository.class);
        VoucherLineRepository lineRepo = mock(VoucherLineRepository.class);
        FinancialYearRepository fyRepo = mock(FinancialYearRepository.class);
        OpeningBalanceRepository obRepo = mock(OpeningBalanceRepository.class);
        LedgerAccountRepository ledgerRepo = mock(LedgerAccountRepository.class);
        AccountGroupRepository groupRepo = mock(AccountGroupRepository.class);

        // Materialise the generated population as Voucher entities with their assigned ids, plus two
        // balanced lines each (so the batch line-load returns something to map into rows).
        List<Voucher> population = new ArrayList<>(scenario.vouchers().size());
        List<VoucherLine> allLines = new ArrayList<>();
        for (VoucherSpec spec : scenario.vouchers()) {
            Voucher voucher = new Voucher(spec.type(), spec.date(), 1L,
                    spec.type().name() + "/" + spec.id(), "Property 19 voucher", "tester", null, null, null);
            setId(voucher, spec.id());
            population.add(voucher);
            allLines.add(new VoucherLine(spec.id(), 100L, BigDecimal.TEN, null, 0, null));
            allLines.add(new VoucherLine(spec.id(), 101L, null, BigDecimal.TEN, 1, null));
        }

        // The mocked finders faithfully honour their declared filtering + ordering contract.
        when(voucherRepo.findByVoucherDateBetweenOrderByVoucherDateAscIdAsc(any(), any()))
                .thenAnswer(inv -> inPeriodSorted(population, inv.getArgument(0), inv.getArgument(1), null));
        for (VoucherType type : VoucherType.values()) {
            when(voucherRepo.findByVoucherTypeAndVoucherDateBetweenOrderByVoucherDateAscIdAsc(
                    eq(type), any(), any()))
                    .thenAnswer(inv -> inPeriodSorted(population, inv.getArgument(1), inv.getArgument(2),
                            inv.getArgument(0)));
        }

        when(lineRepo.findByVoucherIdIn(any())).thenAnswer(inv -> {
            Collection<Long> ids = inv.getArgument(0);
            return allLines.stream().filter(l -> ids.contains(l.getVoucherId())).toList();
        });

        FinancialYearService financialYearService = new FinancialYearService(
                fyRepo, obRepo, voucherRepo, lineRepo, ledgerRepo, groupRepo, null);
        ReportPeriodResolver resolver = new ReportPeriodResolver(financialYearService);
        return new Fixture(new DayBookService(resolver, voucherRepo, lineRepo));
    }

    /** The declared repository contract: date within [from, to], ordered by voucher date then id. */
    private static List<Voucher> inPeriodSorted(List<Voucher> population, LocalDate from, LocalDate to,
                                                VoucherType type) {
        return population.stream()
                .filter(v -> !v.getVoucherDate().isBefore(from) && !v.getVoucherDate().isAfter(to))
                .filter(v -> type == null || v.getVoucherType() == type)
                .sorted(Comparator.comparing(Voucher::getVoucherDate).thenComparing(Voucher::getId))
                .toList();
    }

    // --- id reflection helper (JPA-generated ids have no public setter) --------------------------

    private static void setId(Object entity, Long id) {
        try {
            Field field = entity.getClass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unable to assign id on " + entity.getClass(), e);
        }
    }
}
