package com.shifa.oms.ledger;

import com.shifa.oms.ledger.DayBookService.DayBook;
import com.shifa.oms.ledger.DayBookService.DayBookLine;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Property-based test for {@link DayBookService#dayBook(Long, LocalDate, LocalDate, VoucherType)} —
 * every Day Book row is complete (General Ledger, design Correctness Property 20).
 *
 * <p>Feature: general-ledger-accounting, Property 20: Day Book rows are complete.
 *
 * <p>Property statement: for any voucher returned in the Day Book, its row includes the voucher's
 * reference, voucher date, voucher type, and narration, and its {@link DayBookLine} list reproduces
 * exactly the voucher's lines' {@code (ledgerAccountId, debit, credit)} — same count, same values, in
 * entry order — with each line carrying exactly one of debit/credit as a strictly-positive amount and
 * the other {@code null}.
 *
 * <p><b>Validates: Requirements 13.2</b>
 *
 * <p>This is a <em>service-level</em> property test exercising the real
 * {@link DayBookService#dayBook(Long, LocalDate, LocalDate, VoucherType)}. Per the project's Java 25
 * gotcha (Mockito cannot mock concrete classes), only the Spring Data repository <em>interfaces</em>
 * ({@link VoucherRepository}, {@link VoucherLineRepository}) are Mockito-mocked; the period resolver
 * is a REAL {@link ReportPeriodResolver} over a REAL {@link FinancialYearService} built over those
 * mocked repositories (mirroring the sibling {@link DayBookFilteringPropertyTest}). The reporting
 * period is supplied as an explicit {@code from}/{@code to} range wide enough to contain every
 * generated voucher, so the whole population is returned and each row can be checked for completeness.
 *
 * <p>The mocked finders stay faithful to their declared contract:
 * {@code findByVoucherDateBetweenOrderByVoucherDateAscIdAsc} returns the population filtered to the
 * period and ordered by voucher date then id, and {@code findByVoucherIdIn} returns exactly the lines
 * of the requested vouchers. The oracle indexes the generated population by id and asserts each
 * returned row's metadata and reconstructed lines match its source voucher exactly.
 */
class DayBookRowCompletenessPropertyTest {

    /** A fixed anchor the generated voucher dates (and the enclosing period) are spread around. */
    private static final LocalDate ANCHOR = LocalDate.of(2024, 6, 15);

    // ---------------------------------------------------------------------------------------------
    // Feature: general-ledger-accounting, Property 20: Day Book rows are complete
    // **Validates: Requirements 13.2**
    // ---------------------------------------------------------------------------------------------

    /**
     * Req 13.2: each returned Day Book row carries its voucher's reference/date/type/narration and a
     * line list that reproduces exactly the voucher's lines' (ledgerAccountId, debit, credit), in
     * entry order, each line with exactly one strictly-positive side.
     */
    @Property(tries = 200)
    void dayBookRowsAreComplete(@ForAll("scenarios") Scenario scenario) {
        Fixture f = newFixture(scenario);

        DayBook dayBook = f.service.dayBook(null, scenario.from(), scenario.to(), null);

        Map<Long, VoucherSpec> byId = new HashMap<>();
        for (VoucherSpec spec : scenario.vouchers()) {
            byId.put(spec.id(), spec);
        }

        // Every generated voucher is in-period, so exactly one row per generated voucher is returned.
        assertThat(dayBook.rows())
                .as("one Day Book row per in-period voucher")
                .hasSize(scenario.vouchers().size());

        for (DayBookRow row : dayBook.rows()) {
            VoucherSpec spec = byId.get(row.voucherId());
            assertThat(spec).as("row maps back to a generated voucher").isNotNull();

            // --- metadata completeness (reference/date/type/narration) ---
            assertThat(row.reference())
                    .as("row carries the voucher reference")
                    .isEqualTo(spec.reference());
            assertThat(row.date())
                    .as("row carries the voucher date")
                    .isEqualTo(spec.date());
            assertThat(row.type())
                    .as("row carries the voucher type")
                    .isEqualTo(spec.type());
            assertThat(row.narration())
                    .as("row carries the voucher narration")
                    .isEqualTo(spec.narration());

            // --- line completeness (same count, same values, in entry order) ---
            List<LineSpec> expectedLines = spec.lines();
            assertThat(row.lines())
                    .as("row reproduces every voucher line")
                    .hasSize(expectedLines.size());

            for (int i = 0; i < expectedLines.size(); i++) {
                LineSpec expected = expectedLines.get(i);
                DayBookLine actual = row.lines().get(i);

                assertThat(actual.ledgerAccountId())
                        .as("line %d ledger account matches", i)
                        .isEqualTo(expected.ledgerAccountId());

                if (expected.debit() != null) {
                    assertThat(actual.debit())
                            .as("line %d debit amount matches", i)
                            .isEqualByComparingTo(expected.debit());
                    assertThat(actual.credit())
                            .as("a debit line has no credit amount")
                            .isNull();
                } else {
                    assertThat(actual.credit())
                            .as("line %d credit amount matches", i)
                            .isEqualByComparingTo(expected.credit());
                    assertThat(actual.debit())
                            .as("a credit line has no debit amount")
                            .isNull();
                }

                // Exactly one side is present and strictly positive (the double-entry line invariant).
                boolean exactlyOneSide = (actual.debit() == null) ^ (actual.credit() == null);
                assertThat(exactlyOneSide)
                        .as("line %d has exactly one of debit/credit", i)
                        .isTrue();
                BigDecimal amount = actual.debit() != null ? actual.debit() : actual.credit();
                assertThat(amount)
                        .as("line %d amount is strictly positive", i)
                        .isGreaterThan(BigDecimal.ZERO);
            }
        }
    }

    // --- Generators ------------------------------------------------------------------------------

    @Provide
    Arbitrary<Scenario> scenarios() {
        // A single line: a ledger account, a side (debit/credit), and a strictly-positive amount.
        Arbitrary<LineSpec> lineSpec = Combinators.combine(
                        Arbitraries.longs().between(100L, 999L),
                        Arbitraries.of(true, false),
                        Arbitraries.integers().between(1, 10_000_000))
                .as((ledgerId, isDebit, cents) -> {
                    BigDecimal amount = BigDecimal.valueOf(cents, 2);
                    return isDebit
                            ? new LineSpec(ledgerId, amount, null)
                            : new LineSpec(ledgerId, null, amount);
                });

        // A voucher: a type, a date within a bounded window, and 1..6 lines (a mix of Dr/Cr sides).
        Arbitrary<VoucherSpec> voucherSpec = Combinators.combine(
                        Arbitraries.of(VoucherType.values()),
                        Arbitraries.integers().between(-30, 30),
                        lineSpec.list().ofMinSize(1).ofMaxSize(6))
                .as((type, dayOffset, lines) ->
                        new VoucherSpec(0L, type, ANCHOR.plusDays(dayOffset), null, null, lines));

        Arbitrary<List<VoucherSpec>> vouchers = voucherSpec.list().ofMinSize(0).ofMaxSize(12);
        return vouchers.map(specs -> {
            // Assign each generated voucher a distinct, increasing id and a derived reference/narration.
            List<VoucherSpec> withIds = new ArrayList<>(specs.size());
            long id = 1L;
            for (VoucherSpec spec : specs) {
                long thisId = id++;
                withIds.add(new VoucherSpec(
                        thisId,
                        spec.type(),
                        spec.date(),
                        spec.type().name() + "/" + thisId,
                        "Property 20 voucher " + thisId,
                        spec.lines()));
            }
            // A period wide enough to contain every generated voucher date (filtering is Property 19).
            LocalDate periodFrom = ANCHOR.minusDays(60);
            LocalDate periodTo = ANCHOR.plusDays(60);
            return new Scenario(withIds, periodFrom, periodTo);
        });
    }

    /** One generated voucher line: a ledger account and exactly one strictly-positive side. */
    record LineSpec(Long ledgerAccountId, BigDecimal debit, BigDecimal credit) {
    }

    /** One generated voucher: id, type, date, reference, narration, and its ordered lines. */
    record VoucherSpec(Long id,
                       VoucherType type,
                       LocalDate date,
                       String reference,
                       String narration,
                       List<LineSpec> lines) {
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

        // Materialise the generated population as Voucher entities (with ids) and their lines. Each
        // line gets lineOrder = its generation index so the service's (lineOrder, id) sort reproduces
        // entry order (ids are unset/null and are only a tiebreaker on equal lineOrder — never hit).
        List<Voucher> population = new ArrayList<>(scenario.vouchers().size());
        List<VoucherLine> allLines = new ArrayList<>();
        for (VoucherSpec spec : scenario.vouchers()) {
            Voucher voucher = new Voucher(spec.type(), spec.date(), 1L,
                    spec.reference(), spec.narration(), "tester", null, null, null);
            setId(voucher, spec.id());
            population.add(voucher);

            int lineOrder = 0;
            for (LineSpec line : spec.lines()) {
                allLines.add(new VoucherLine(spec.id(), line.ledgerAccountId(),
                        line.debit(), line.credit(), lineOrder++, null));
            }
        }

        // The mocked finder faithfully honours its declared filtering + ordering contract.
        when(voucherRepo.findByVoucherDateBetweenOrderByVoucherDateAscIdAsc(any(), any()))
                .thenAnswer(inv -> inPeriodSorted(population, inv.getArgument(0), inv.getArgument(1)));

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
    private static List<Voucher> inPeriodSorted(List<Voucher> population, LocalDate from, LocalDate to) {
        return population.stream()
                .filter(v -> !v.getVoucherDate().isBefore(from) && !v.getVoucherDate().isAfter(to))
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
