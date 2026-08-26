package com.shifa.oms.ledger;

import com.shifa.oms.ledger.domain.AccountNature;
import com.shifa.oms.ledger.domain.BalanceMath;
import com.shifa.oms.ledger.domain.BalanceMath.SidedBalance;
import com.shifa.oms.ledger.domain.DrCr;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Property-based test for {@link FinancialYearService#openNext(Long)} — closing balances carry
 * forward as the next financial year's opening balances (General Ledger, design Correctness
 * Property 7).
 *
 * <p>Feature: general-ledger-accounting, Property 7: Closing balances carry forward as next-year
 * opening balances.
 *
 * <p>Property statement: for any ledger account with activity (an opening balance and/or posted
 * voucher lines) in a financial year, opening the next financial year produces an opening balance
 * for that account whose side and magnitude equal the account's closing balance in the prior year;
 * an account whose closing balance nets to zero carries nothing forward.
 *
 * <p><b>Validates: Requirements 3.4</b>
 *
 * <p>This is a <em>service-level</em> property: it drives the real {@link FinancialYearService}
 * over lightweight in-memory fakes of its repository <em>interfaces</em>. Per the project's Java 25
 * gotcha (concrete classes cannot be Mockito-mocked) only the Spring Data <em>interfaces</em> are
 * mocked, each backed by a {@link HashMap}/{@link ArrayList} so {@code save}/finder methods behave
 * like a real store (the next FY is get-or-created; carried opening balances are captured on save).
 * The oracle is the pure {@link BalanceMath} sign convention the design mandates: an account's
 * closing signed balance = its opening signed balance + the net signed movement of the source year's
 * posted lines, and {@link BalanceMath#closingSide} turns that into the {@code (side, magnitude)}
 * that must be persisted as the next year's opening balance.
 */
class ClosingBalanceCarryForwardPropertyTest {

    /** Codebase-wide money scale (matches {@link FinancialYearService}). */
    private static final int MONEY_SCALE = 2;

    private static final Long SOURCE_FY_ID = 1L;
    private static final LocalDate SOURCE_START = LocalDate.of(2024, 4, 1);
    private static final LocalDate SOURCE_END = LocalDate.of(2025, 3, 31);

    // ---------------------------------------------------------------------------------------------
    // Feature: general-ledger-accounting, Property 7: Closing balances carry forward as next-year
    // opening balances
    // **Validates: Requirements 3.4**
    // ---------------------------------------------------------------------------------------------

    /**
     * For any set of accounts each carrying an optional opening balance and any number of posted
     * lines in the source year, {@code openNext} persists — for each account whose closing balance is
     * non-zero — a next-year opening balance whose side and magnitude equal
     * {@code BalanceMath.closingSide(nature, openingSigned + netMovementSigned)}; accounts whose
     * closing balance nets to zero carry nothing forward.
     */
    @Property(tries = 200)
    void closingBalancesCarryForwardAsNextYearOpenings(@ForAll("scenarios") List<AccountSpec> specs) {
        Fixture f = newFixture(specs);

        FinancialYear next = f.service.openNext(SOURCE_FY_ID);

        // A next financial year distinct from the source is opened (get-or-create).
        assertThat(next.getId()).isNotNull().isNotEqualTo(SOURCE_FY_ID);
        assertThat(next.getStartDate()).isEqualTo(SOURCE_END.plusDays(1));

        for (int i = 0; i < specs.size(); i++) {
            AccountSpec spec = specs.get(i);
            Long ledgerId = f.ledgerIdFor(i);

            SidedBalance expected = expectedClosing(spec);
            OpeningBalance carried = f.captured.get(ledgerId);

            if (expected == null) {
                // Closing nets to zero -> nothing is carried forward for this account.
                assertThat(carried)
                        .as("account #%d (nature %s) with zero closing carries nothing forward", i, spec.nature())
                        .isNull();
            } else {
                assertThat(carried)
                        .as("account #%d (nature %s) carries a next-year opening balance", i, spec.nature())
                        .isNotNull();
                assertThat(carried.getFinancialYearId())
                        .as("carried opening recorded against the next FY")
                        .isEqualTo(next.getId());
                assertThat(carried.getSide())
                        .as("carried opening side equals the prior-year closing side")
                        .isEqualTo(expected.side());
                assertThat(carried.getAmount())
                        .as("carried opening magnitude equals the prior-year closing magnitude")
                        .usingComparator(BigDecimal::compareTo)
                        .isEqualTo(expected.magnitude());
            }
        }
    }

    /**
     * The oracle: recompute the account's source-year closing balance via the pure
     * {@link BalanceMath} sign convention (exactly as the design mandates). Returns {@code null} when
     * the closing balance nets to zero (nothing carries forward).
     */
    private static SidedBalance expectedClosing(AccountSpec spec) {
        BigDecimal signed = BigDecimal.ZERO;
        if (spec.opening().isPresent()) {
            Sided o = spec.opening().get();
            signed = signed.add(BalanceMath.signedDelta(spec.nature(), o.side(), o.amount()));
        }
        for (Sided line : spec.lines()) {
            signed = signed.add(BalanceMath.signedDelta(spec.nature(), line.side(), line.amount()));
        }
        signed = signed.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        SidedBalance closing = BalanceMath.closingSide(spec.nature(), signed);
        return closing.magnitude().signum() <= 0 ? null : closing;
    }

    // --- Generators ------------------------------------------------------------------------------

    @Provide
    Arbitrary<List<AccountSpec>> scenarios() {
        return accountSpec().list().ofMinSize(1).ofMaxSize(6);
    }

    private Arbitrary<AccountSpec> accountSpec() {
        Arbitrary<AccountNature> nature = Arbitraries.of(AccountNature.values());
        Arbitrary<Optional<Sided>> opening = sided().optional();
        Arbitrary<List<Sided>> lines = sided().list().ofMinSize(0).ofMaxSize(5);
        return Combinators.combine(nature, opening, lines).as(AccountSpec::new);
    }

    /** A strictly-positive scale-2 amount on exactly one side (the voucher-line invariant). */
    private Arbitrary<Sided> sided() {
        Arbitrary<DrCr> side = Arbitraries.of(DrCr.DEBIT, DrCr.CREDIT);
        Arbitrary<BigDecimal> amount = Arbitraries.longs().between(1L, 100_000_000L)
                .map(cents -> BigDecimal.valueOf(cents, MONEY_SCALE));
        return Combinators.combine(side, amount).as(Sided::new);
    }

    /** One account's activity in the source year: a nature, an optional opening, and posted lines. */
    record Sided(DrCr side, BigDecimal amount) {
    }

    record AccountSpec(AccountNature nature, Optional<Sided> opening, List<Sided> lines) {
    }

    // --- In-memory repository fixture ------------------------------------------------------------

    private static final class Fixture {
        final FinancialYearService service;
        final Map<Long, OpeningBalance> captured; // keyed by ledgerAccountId (only next-FY saves occur)

        Fixture(FinancialYearService service, Map<Long, OpeningBalance> captured) {
            this.service = service;
            this.captured = captured;
        }

        Long ledgerIdFor(int index) {
            return 100L + index;
        }
    }

    private static Fixture newFixture(List<AccountSpec> specs) {
        FinancialYearRepository fyRepo = mock(FinancialYearRepository.class);
        OpeningBalanceRepository obRepo = mock(OpeningBalanceRepository.class);
        VoucherRepository voucherRepo = mock(VoucherRepository.class);
        VoucherLineRepository lineRepo = mock(VoucherLineRepository.class);
        LedgerAccountRepository ledgerRepo = mock(LedgerAccountRepository.class);
        AccountGroupRepository groupRepo = mock(AccountGroupRepository.class);

        // --- Financial years: source (id 1) pre-existing; next created on demand -----------------
        Map<Long, FinancialYear> years = new HashMap<>();
        FinancialYear source = new FinancialYear(SOURCE_START, SOURCE_END, "2024-25");
        setId(source, SOURCE_FY_ID);
        years.put(SOURCE_FY_ID, source);
        AtomicLong fySeq = new AtomicLong(SOURCE_FY_ID);

        when(fyRepo.findById(anyLong()))
                .thenAnswer(inv -> Optional.ofNullable(years.get(inv.<Long>getArgument(0))));
        when(fyRepo.findFirstByStartDateLessThanEqualAndEndDateGreaterThanEqual(any(), any()))
                .thenAnswer(inv -> {
                    LocalDate date = inv.getArgument(0);
                    return years.values().stream()
                            .filter(fy -> !date.isBefore(fy.getStartDate()) && !date.isAfter(fy.getEndDate()))
                            .findFirst();
                });
        when(fyRepo.findByStartDate(any())).thenAnswer(inv -> {
            LocalDate start = inv.getArgument(0);
            return years.values().stream().filter(fy -> fy.getStartDate().equals(start)).findFirst();
        });
        when(fyRepo.save(any(FinancialYear.class))).thenAnswer(inv -> {
            FinancialYear fy = inv.getArgument(0);
            if (getId(fy) == null) {
                setId(fy, fySeq.incrementAndGet());
            }
            years.put(getId(fy), fy);
            return fy;
        });

        // --- Ledger accounts + owning groups (nature per spec) -----------------------------------
        Map<Long, LedgerAccount> ledgers = new HashMap<>();
        Map<Long, AccountGroup> groups = new HashMap<>();
        List<OpeningBalance> sourceOpenings = new ArrayList<>();
        List<VoucherLine> sourceLines = new ArrayList<>();
        Long sourceVoucherId = 9001L;

        for (int i = 0; i < specs.size(); i++) {
            AccountSpec spec = specs.get(i);
            Long ledgerId = 100L + i;
            Long groupId = 200L + i;

            AccountGroup group = new AccountGroup("Group " + i, spec.nature(), null, false);
            setId(group, groupId);
            groups.put(groupId, group);

            LedgerAccount ledger = new LedgerAccount("Ledger " + i, groupId, null, false);
            setId(ledger, ledgerId);
            ledgers.put(ledgerId, ledger);

            spec.opening().ifPresent(o ->
                    sourceOpenings.add(new OpeningBalance(ledgerId, SOURCE_FY_ID, o.amount(), o.side())));
            for (Sided line : spec.lines()) {
                BigDecimal debit = line.side() == DrCr.DEBIT ? line.amount() : null;
                BigDecimal credit = line.side() == DrCr.CREDIT ? line.amount() : null;
                sourceLines.add(new VoucherLine(sourceVoucherId, ledgerId, debit, credit, 0, null));
            }
        }

        when(ledgerRepo.findById(anyLong()))
                .thenAnswer(inv -> Optional.ofNullable(ledgers.get(inv.<Long>getArgument(0))));
        when(groupRepo.findById(anyLong()))
                .thenAnswer(inv -> Optional.ofNullable(groups.get(inv.<Long>getArgument(0))));

        // --- Vouchers + lines for the source FY --------------------------------------------------
        Voucher sourceVoucher = new Voucher(com.shifa.oms.ledger.domain.VoucherType.JOURNAL,
                SOURCE_START, SOURCE_FY_ID, "JR/2024-25/1", "seed", "tester", null, null, null);
        setId(sourceVoucher, sourceVoucherId);
        when(voucherRepo.findByFinancialYearId(SOURCE_FY_ID)).thenReturn(List.of(sourceVoucher));
        when(voucherRepo.findByFinancialYearId(anyLong())).thenAnswer(inv -> {
            Long fyId = inv.getArgument(0);
            return SOURCE_FY_ID.equals(fyId) ? List.of(sourceVoucher) : List.<Voucher>of();
        });
        when(lineRepo.findByVoucherIdIn(any())).thenAnswer(inv -> {
            java.util.Collection<Long> ids = inv.getArgument(0);
            return sourceLines.stream().filter(l -> ids.contains(l.getVoucherId())).toList();
        });

        // --- Opening balances: source read; next-FY writes captured ------------------------------
        when(obRepo.findByFinancialYearId(anyLong())).thenAnswer(inv -> {
            Long fyId = inv.getArgument(0);
            return SOURCE_FY_ID.equals(fyId) ? new ArrayList<>(sourceOpenings) : new ArrayList<OpeningBalance>();
        });
        when(obRepo.findByLedgerAccountIdAndFinancialYearId(anyLong(), anyLong()))
                .thenReturn(Optional.empty()); // next FY starts with no openings -> service inserts
        Map<Long, OpeningBalance> captured = new HashMap<>();
        when(obRepo.save(any(OpeningBalance.class))).thenAnswer(inv -> {
            OpeningBalance ob = inv.getArgument(0);
            captured.put(ob.getLedgerAccountId(), ob);
            return ob;
        });

        FinancialYearService service = new FinancialYearService(
                fyRepo, obRepo, voucherRepo, lineRepo, ledgerRepo, groupRepo, null);
        return new Fixture(service, captured);
    }

    // --- id reflection helpers (JPA-generated ids have no public setter) -------------------------

    private static Long getId(Object entity) {
        return (Long) readField(entity, "id");
    }

    private static void setId(Object entity, Long id) {
        writeField(entity, "id", id);
    }

    private static Object readField(Object target, String fieldName) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unable to read field '" + fieldName + "'", e);
        }
    }

    private static void writeField(Object target, String fieldName, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unable to write field '" + fieldName + "'", e);
        }
    }
}
