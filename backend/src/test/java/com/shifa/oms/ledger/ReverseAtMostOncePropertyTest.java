package com.shifa.oms.ledger;

import com.shifa.oms.audit.AuditEvent;
import com.shifa.oms.audit.AuditEventRepository;
import com.shifa.oms.audit.AuditService;
import com.shifa.oms.auth.CurrentUserService;
import com.shifa.oms.common.ValidationException;
import com.shifa.oms.ledger.VoucherService.PostVoucherCommand;
import com.shifa.oms.ledger.VoucherService.VoucherLineCommand;
import com.shifa.oms.ledger.domain.AccountNature;
import com.shifa.oms.ledger.domain.DrCr;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Property-based test for {@link VoucherService#reverse(Long)} — a voucher can be reversed at most
 * once (General Ledger, design Correctness Property 13).
 *
 * <p>Feature: general-ledger-accounting, Property 13: A voucher can be reversed at most once.
 *
 * <p>Property statement: for any posted voucher, reversing it once succeeds — a balanced reversing
 * voucher is created and the original's {@code reversedByVoucherId} link is set — and any subsequent
 * attempt to reverse the same voucher is rejected with a validation error and creates no further
 * voucher.
 *
 * <p><b>Validates: Requirements 6.5</b>
 *
 * <p>This is a <em>service-level</em> property test that exercises the real
 * {@link VoucherService#post(PostVoucherCommand)} and {@link VoucherService#reverse(Long)}. Per the
 * project's Java 25 gotcha (Mockito cannot mock concrete classes), only the Spring Data repository
 * <em>interfaces</em> are Mockito-mocked; the concrete collaborators are built as REAL instances over
 * those mocks: a real (open) {@link FinancialYearService}, a real {@link VoucherReferenceSequencer}
 * over an in-memory sequence store, a real never-throwing {@link AuditService} over a mocked audit
 * repository, and a real {@link CurrentUserService}. Crucially, the mocked {@link VoucherRepository}
 * assigns ids on {@code save} and returns the stored voucher from {@code findById}, and the mocked
 * {@link VoucherLineRepository} returns the stored lines from
 * {@code findByVoucherIdOrderByLineOrderAsc} — so the {@link Voucher#markReversedBy(Long)}
 * persistence is observable and the {@code reversed_by} link is read back on the second reverse
 * attempt, which is exactly what the at-most-once guard depends on.
 */
class ReverseAtMostOncePropertyTest {

    /** Money scale matching the codebase-wide {@code BigDecimal} scale-2 {@code HALF_UP} convention. */
    private static final int MONEY_SCALE = 2;

    private static final Long FY_ID = 1L;
    /** Ledger id used for the single credit line (natures are irrelevant to balancing/reversal). */
    private static final Long CREDIT_LEDGER_ID = 200L;
    /** Base id for the generated debit ledgers (100, 101, ...). */
    private static final long DEBIT_LEDGER_BASE = 100L;

    // ---------------------------------------------------------------------------------------------
    // Feature: general-ledger-accounting, Property 13: A voucher can be reversed at most once
    // **Validates: Requirements 6.5**
    // ---------------------------------------------------------------------------------------------

    /**
     * Req 6.5: for any posted voucher, the first {@code reverse} succeeds (a reversing voucher is
     * created and the original is linked to it) and a second {@code reverse} of the same voucher is
     * rejected with a validation error, creating no further voucher.
     */
    @Property(tries = 200)
    void aVoucherCanBeReversedAtMostOnce(@ForAll("balancedVouchers") BalancedVoucher spec) {
        Fixture f = newFixture(spec);

        // Seed a valid, posted voucher.
        Voucher original = f.service.post(spec.command());
        assertThat(original).as("the seed voucher posts successfully").isNotNull();
        assertThat(f.savedVouchers).hasSize(1);
        assertThat(original.getReversedByVoucherId()).as("a freshly posted voucher is not reversed").isNull();

        // First reverse: succeeds and creates exactly one reversing voucher.
        Voucher reversing = f.service.reverse(original.getId());
        assertThat(reversing).as("the first reverse succeeds").isNotNull();
        assertThat(f.savedVouchers).as("posting + one reverse persists exactly two vouchers").hasSize(2);

        // Req 6.4: the bidirectional reversal link is established.
        assertThat(reversing.getReversesVoucherId())
                .as("the reversing voucher points back at the original")
                .isEqualTo(original.getId());
        assertThat(original.getReversedByVoucherId())
                .as("the original is linked to its reversing voucher")
                .isEqualTo(reversing.getId());

        // Second reverse of the SAME voucher: rejected, and nothing further is persisted.
        assertThatThrownBy(() -> f.service.reverse(original.getId()))
                .as("a second reverse of the same voucher is rejected")
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("already been reversed");

        assertThat(f.savedVouchers)
                .as("a rejected second reverse creates no further voucher")
                .hasSize(2);
        assertThat(original.getReversedByVoucherId())
                .as("the original stays linked to its FIRST (only) reversing voucher")
                .isEqualTo(reversing.getId());
    }

    // --- Generators ------------------------------------------------------------------------------

    @Provide
    Arbitrary<BalancedVoucher> balancedVouchers() {
        Arbitrary<Integer> startYears = Arbitraries.integers().between(2000, 2099);
        Arbitrary<Integer> dayOffsets = Arbitraries.integers().between(0, 400);
        // 1..4 strictly-positive debit amounts; a single credit line balances their sum.
        Arbitrary<List<Long>> debitAmounts = Arbitraries.longs().between(1L, 100_000_000L)
                .list().ofMinSize(1).ofMaxSize(4);
        return Combinators.combine(startYears, dayOffsets, debitAmounts)
                .as((startYear, dayOffset, amountCents) -> {
                    FinancialYearService.FinancialYearWindow window =
                            FinancialYearService.windowFor(LocalDate.of(startYear, 4, 1));
                    long span = ChronoUnit.DAYS.between(window.startDate(), window.endDate());
                    LocalDate date = window.startDate().plusDays(Math.min(dayOffset, span));
                    return new BalancedVoucher(window, date, amountCents);
                });
    }

    /**
     * One generated, balanced multi-line voucher: a financial-year window, a voucher date within it,
     * and the strictly-positive debit amounts (each becomes a debit line; a single credit line of
     * their sum balances the voucher). Since balancing/reversal is nature-independent, the ledgers'
     * natures are fixed in the fixture.
     */
    record BalancedVoucher(FinancialYearService.FinancialYearWindow window,
                           LocalDate voucherDate,
                           List<Long> debitAmountCents) {

        /** A balanced JOURNAL command: one debit line per amount + a single balancing credit line. */
        PostVoucherCommand command() {
            List<VoucherLineCommand> lines = new ArrayList<>();
            BigDecimal creditTotal = BigDecimal.ZERO;
            for (int i = 0; i < debitAmountCents.size(); i++) {
                BigDecimal amount = BigDecimal.valueOf(debitAmountCents.get(i), MONEY_SCALE);
                lines.add(new VoucherLineCommand(DEBIT_LEDGER_BASE + i, DrCr.DEBIT, amount));
                creditTotal = creditTotal.add(amount);
            }
            lines.add(new VoucherLineCommand(CREDIT_LEDGER_ID, DrCr.CREDIT, creditTotal));
            return new PostVoucherCommand("JOURNAL", voucherDate, "Property 13 test voucher", lines);
        }
    }

    // --- Fixture: VoucherService over mocked repository interfaces + real collaborators ----------

    private static final class Fixture {
        final VoucherService service;
        final List<Voucher> savedVouchers;

        Fixture(VoucherService service, List<Voucher> savedVouchers) {
            this.service = service;
            this.savedVouchers = savedVouchers;
        }
    }

    private static Fixture newFixture(BalancedVoucher spec) {
        VoucherRepository voucherRepo = mock(VoucherRepository.class);
        VoucherLineRepository lineRepo = mock(VoucherLineRepository.class);
        LedgerAccountRepository ledgerRepo = mock(LedgerAccountRepository.class);
        AccountGroupRepository groupRepo = mock(AccountGroupRepository.class);
        FinancialYearRepository fyRepo = mock(FinancialYearRepository.class);
        OpeningBalanceRepository obRepo = mock(OpeningBalanceRepository.class);
        LedgerVoucherSequenceRepository seqRepo = mock(LedgerVoucherSequenceRepository.class);
        AuditEventRepository auditRepo = mock(AuditEventRepository.class);

        // --- An OPEN financial year that contains the voucher date (so the reversing post is accepted).
        FinancialYear fy = new FinancialYear(
                spec.window().startDate(), spec.window().endDate(), spec.window().label());
        setId(fy, FY_ID);
        fy.setClosed(false);
        when(fyRepo.findFirstByStartDateLessThanEqualAndEndDateGreaterThanEqual(any(), any()))
                .thenReturn(Optional.of(fy));
        when(fyRepo.findById(FY_ID)).thenReturn(Optional.of(fy));
        when(fyRepo.findByStartDate(any())).thenReturn(Optional.of(fy));

        // --- One valid ledger per debit line + the single credit ledger (fixed natures).
        Map<Long, LedgerAccount> ledgers = new HashMap<>();
        Map<Long, AccountGroup> groups = new HashMap<>();
        for (int i = 0; i < spec.debitAmountCents().size(); i++) {
            registerLedger(ledgers, groups, DEBIT_LEDGER_BASE + i, 300L + i, AccountNature.EXPENSE);
        }
        registerLedger(ledgers, groups, CREDIT_LEDGER_ID, 400L, AccountNature.ASSET);
        when(ledgerRepo.findById(anyLong()))
                .thenAnswer(inv -> Optional.ofNullable(ledgers.get(inv.<Long>getArgument(0))));
        when(groupRepo.findById(anyLong()))
                .thenAnswer(inv -> Optional.ofNullable(groups.get(inv.<Long>getArgument(0))));

        // --- Recording voucher/line persistence: save assigns ids and findById returns the stored
        //     voucher; saved lines are retrievable in entry order — so markReversedBy is observable
        //     and the reversed_by link is read back on the second reverse attempt.
        List<Voucher> savedVouchers = new ArrayList<>();
        Map<Long, Voucher> vouchersById = new HashMap<>();
        Map<Long, List<VoucherLine>> linesByVoucherId = new HashMap<>();
        AtomicLong voucherSeq = new AtomicLong(500L);
        when(voucherRepo.save(any(Voucher.class))).thenAnswer(inv -> {
            Voucher v = inv.getArgument(0);
            if (readId(v) == null) {
                setId(v, voucherSeq.incrementAndGet());
                savedVouchers.add(v);
            }
            vouchersById.put(readId(v), v);
            return v;
        });
        when(voucherRepo.findById(anyLong()))
                .thenAnswer(inv -> Optional.ofNullable(vouchersById.get(inv.<Long>getArgument(0))));
        when(lineRepo.saveAll(any())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            Iterable<VoucherLine> lines = (Iterable<VoucherLine>) inv.getArgument(0);
            List<VoucherLine> persisted = new ArrayList<>();
            lines.forEach(persisted::add);
            for (VoucherLine line : persisted) {
                linesByVoucherId.computeIfAbsent(line.getVoucherId(), k -> new ArrayList<>()).add(line);
            }
            return persisted;
        });
        when(lineRepo.findByVoucherIdOrderByLineOrderAsc(anyLong())).thenAnswer(inv -> {
            List<VoucherLine> lines = linesByVoucherId.getOrDefault(inv.<Long>getArgument(0), List.of());
            List<VoucherLine> ordered = new ArrayList<>(lines);
            ordered.sort(Comparator.comparingInt(VoucherLine::getLineOrder));
            return ordered;
        });

        // --- In-memory voucher-reference sequence store (mocked interface, real sequencer).
        Map<String, LedgerVoucherSequence> sequenceRows = new ConcurrentHashMap<>();
        when(seqRepo.findByIdForUpdate(any(), anyLong())).thenAnswer(inv ->
                Optional.ofNullable(sequenceRows.get(inv.getArgument(0) + "|" + inv.<Long>getArgument(1))));
        when(seqRepo.save(any(LedgerVoucherSequence.class))).thenAnswer(inv -> {
            LedgerVoucherSequence row = inv.getArgument(0);
            sequenceRows.put(row.getVoucherType() + "|" + row.getFinancialYearId(), row);
            return row;
        });

        // --- Audit repository just echoes the saved event (AuditService is never-throwing anyway).
        when(auditRepo.save(any(AuditEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        // --- Real concrete collaborators over the mocked interfaces (Java 25: no mocking concretes).
        CurrentUserService currentUserService = new CurrentUserService();
        FinancialYearService financialYearService = new FinancialYearService(
                fyRepo, obRepo, voucherRepo, lineRepo, ledgerRepo, groupRepo, currentUserService);
        VoucherReferenceSequencer sequencer = new VoucherReferenceSequencer(seqRepo);
        AuditService auditService = new AuditService(auditRepo, currentUserService);

        VoucherService service = new VoucherService(voucherRepo, lineRepo, ledgerRepo, groupRepo,
                financialYearService, sequencer, currentUserService, auditService);

        return new Fixture(service, savedVouchers);
    }

    private static void registerLedger(Map<Long, LedgerAccount> ledgers, Map<Long, AccountGroup> groups,
                                       Long ledgerId, Long groupId, AccountNature nature) {
        AccountGroup group = new AccountGroup("Group " + groupId, nature, null, false);
        setId(group, groupId);
        groups.put(groupId, group);
        LedgerAccount ledger = new LedgerAccount("Ledger " + ledgerId, groupId, null, false);
        setId(ledger, ledgerId);
        ledgers.put(ledgerId, ledger);
    }

    // --- id reflection helpers (JPA-generated ids have no public setter) -------------------------

    private static Long readId(Object entity) {
        try {
            Field field = entity.getClass().getDeclaredField("id");
            field.setAccessible(true);
            return (Long) field.get(entity);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unable to read id on " + entity.getClass(), e);
        }
    }

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
