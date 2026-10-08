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
 * Property-based test for {@link VoucherService#post(PostVoucherCommand)} — a closed financial year
 * rejects posting (General Ledger, design Correctness Property 6).
 *
 * <p>Feature: general-ledger-accounting, Property 6: A closed financial year rejects posting.
 *
 * <p>Property statement: for any financial year marked closed and any voucher date within that year,
 * posting an otherwise-valid balanced voucher is rejected with a validation error (mentioning the
 * year is closed) and persists no voucher; the very same voucher posted into an open year is
 * accepted and persisted.
 *
 * <p><b>Validates: Requirements 4.3</b>
 *
 * <p>This is a <em>service-level</em> property test that exercises the real
 * {@link VoucherService#post(PostVoucherCommand)}. Per the project's Java 25 gotcha (Mockito cannot
 * mock concrete classes), only the Spring Data repository <em>interfaces</em> are Mockito-mocked; the
 * concrete collaborators are built as REAL instances over those mocks: a real
 * {@link FinancialYearService} (whose resolved financial year carries the generated {@code closed}
 * flag), a real {@link VoucherReferenceSequencer} over an in-memory sequence store, a real
 * {@link AuditService} (never-throwing) over a mocked audit repository, and a real
 * {@link CurrentUserService} (unauthenticated in a plain unit test, so the acting user resolves to
 * {@code null}). The draft handed in is always a balanced two-line voucher with valid ledgers so the
 * <em>only</em> variable deciding accept/reject is whether its financial year is closed.
 */
class ClosedFinancialYearPostingPropertyTest {

    /** Money scale matching the codebase-wide {@code BigDecimal} scale-2 {@code HALF_UP} convention. */
    private static final int MONEY_SCALE = 2;

    private static final Long FY_ID = 1L;
    private static final Long DEBIT_LEDGER_ID = 100L;
    private static final Long CREDIT_LEDGER_ID = 101L;

    // ---------------------------------------------------------------------------------------------
    // Feature: general-ledger-accounting, Property 6: A closed financial year rejects posting
    // **Validates: Requirements 4.3**
    // ---------------------------------------------------------------------------------------------

    /**
     * Req 4.3: for any voucher date within a financial year, posting an otherwise-valid balanced
     * voucher is accepted iff that year is open — a closed year rejects the post with a validation
     * error mentioning the year is closed and persists nothing, while an open year persists exactly
     * one voucher.
     */
    @Property(tries = 200)
    void postingIsAcceptedIffTheFinancialYearIsOpen(@ForAll("scenarios") Scenario scenario,
                                                    @ForAll boolean closed) {
        Fixture f = newFixture(scenario, closed);
        PostVoucherCommand command = balancedCommand(scenario);

        if (closed) {
            assertThatThrownBy(() -> f.service.post(command))
                    .as("posting into a CLOSED financial year is rejected")
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("closed");
            // Req 4.3: nothing is persisted when the target year is closed.
            assertThat(f.savedVouchers)
                    .as("a closed financial year persists no voucher")
                    .isEmpty();
            assertThat(f.savedLines)
                    .as("a closed financial year persists no voucher lines")
                    .isEmpty();
        } else {
            Voucher posted = f.service.post(command);
            assertThat(posted).as("posting into an OPEN financial year succeeds").isNotNull();
            assertThat(posted.getFinancialYearId()).isEqualTo(FY_ID);
            assertThat(f.savedVouchers)
                    .as("an open financial year persists exactly one voucher")
                    .hasSize(1);
        }
    }

    /**
     * Req 4.3 (the contrast the property statement demands): the <em>same</em> balanced voucher,
     * dated within the same financial year, is rejected when that year is closed yet accepted when it
     * is open.
     */
    @Property(tries = 200)
    void theSameVoucherIsRejectedWhenClosedButAcceptedWhenOpen(@ForAll("scenarios") Scenario scenario) {
        Fixture closedFy = newFixture(scenario, true);
        Fixture openFy = newFixture(scenario, false);

        assertThatThrownBy(() -> closedFy.service.post(balancedCommand(scenario)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("closed");
        assertThat(closedFy.savedVouchers).isEmpty();

        assertThat(openFy.service.post(balancedCommand(scenario))).isNotNull();
        assertThat(openFy.savedVouchers).hasSize(1);
    }

    // --- Command builder -------------------------------------------------------------------------

    /** A balanced two-line JOURNAL voucher dated within the scenario's financial year. */
    private static PostVoucherCommand balancedCommand(Scenario scenario) {
        BigDecimal amount = BigDecimal.valueOf(scenario.amountCents(), MONEY_SCALE);
        List<VoucherLineCommand> lines = List.of(
                new VoucherLineCommand(DEBIT_LEDGER_ID, DrCr.DEBIT, amount),
                new VoucherLineCommand(CREDIT_LEDGER_ID, DrCr.CREDIT, amount));
        return new PostVoucherCommand("JOURNAL", scenario.voucherDate(), "Property 6 test voucher", lines);
    }

    // --- Generators ------------------------------------------------------------------------------

    @Provide
    Arbitrary<Scenario> scenarios() {
        Arbitrary<Integer> startYears = Arbitraries.integers().between(2000, 2099);
        Arbitrary<Integer> dayOffsets = Arbitraries.integers().between(0, 400);
        Arbitrary<Long> amounts = Arbitraries.longs().between(1L, 100_000_000L);
        Arbitrary<AccountNature> debitNatures = Arbitraries.of(AccountNature.values());
        Arbitrary<AccountNature> creditNatures = Arbitraries.of(AccountNature.values());
        return Combinators.combine(startYears, dayOffsets, amounts, debitNatures, creditNatures)
                .as((startYear, dayOffset, amountCents, debitNature, creditNature) -> {
                    FinancialYearService.FinancialYearWindow window =
                            FinancialYearService.windowFor(LocalDate.of(startYear, 4, 1));
                    long span = ChronoUnit.DAYS.between(window.startDate(), window.endDate());
                    LocalDate date = window.startDate().plusDays(Math.min(dayOffset, span));
                    return new Scenario(window, date, amountCents, debitNature, creditNature);
                });
    }

    /**
     * One generated scenario: a financial-year window, a voucher date within it, a balanced amount,
     * and the natures of the two ledgers (nature is irrelevant to balancing, so any pair is valid).
     */
    record Scenario(FinancialYearService.FinancialYearWindow window,
                    LocalDate voucherDate,
                    long amountCents,
                    AccountNature debitNature,
                    AccountNature creditNature) {
    }

    // --- Fixture: VoucherService over mocked repository interfaces + real collaborators ----------

    private static final class Fixture {
        final VoucherService service;
        final List<Voucher> savedVouchers;
        final List<VoucherLine> savedLines;

        Fixture(VoucherService service, List<Voucher> savedVouchers, List<VoucherLine> savedLines) {
            this.service = service;
            this.savedVouchers = savedVouchers;
            this.savedLines = savedLines;
        }
    }

    private static Fixture newFixture(Scenario scenario, boolean closed) {
        VoucherRepository voucherRepo = mock(VoucherRepository.class);
        VoucherLineRepository lineRepo = mock(VoucherLineRepository.class);
        LedgerAccountRepository ledgerRepo = mock(LedgerAccountRepository.class);
        AccountGroupRepository groupRepo = mock(AccountGroupRepository.class);
        FinancialYearRepository fyRepo = mock(FinancialYearRepository.class);
        OpeningBalanceRepository obRepo = mock(OpeningBalanceRepository.class);
        LedgerVoucherSequenceRepository seqRepo = mock(LedgerVoucherSequenceRepository.class);
        AuditEventRepository auditRepo = mock(AuditEventRepository.class);

        // --- The financial year that contains the voucher date, carrying the generated closed flag.
        FinancialYear fy = new FinancialYear(
                scenario.window().startDate(), scenario.window().endDate(), scenario.window().label());
        setId(fy, FY_ID);
        fy.setClosed(closed);
        when(fyRepo.findFirstByStartDateLessThanEqualAndEndDateGreaterThanEqual(any(), any()))
                .thenReturn(Optional.of(fy));
        when(fyRepo.findById(FY_ID)).thenReturn(Optional.of(fy));
        when(fyRepo.findByStartDate(any())).thenReturn(Optional.of(fy));

        // --- Two valid ledgers, each under a group whose nature comes from the scenario.
        Map<Long, LedgerAccount> ledgers = new HashMap<>();
        Map<Long, AccountGroup> groups = new HashMap<>();
        registerLedger(ledgers, groups, DEBIT_LEDGER_ID, 200L, scenario.debitNature());
        registerLedger(ledgers, groups, CREDIT_LEDGER_ID, 201L, scenario.creditNature());
        when(ledgerRepo.findById(anyLong()))
                .thenAnswer(inv -> Optional.ofNullable(ledgers.get(inv.<Long>getArgument(0))));
        when(groupRepo.findById(anyLong()))
                .thenAnswer(inv -> Optional.ofNullable(groups.get(inv.<Long>getArgument(0))));

        // --- Recording voucher/line persistence (voucher gets an id on save).
        List<Voucher> savedVouchers = new ArrayList<>();
        List<VoucherLine> savedLines = new ArrayList<>();
        AtomicLong voucherSeq = new AtomicLong(500L);
        when(voucherRepo.save(any(Voucher.class))).thenAnswer(inv -> {
            Voucher v = inv.getArgument(0);
            if (readId(v) == null) {
                setId(v, voucherSeq.incrementAndGet());
            }
            savedVouchers.add(v);
            return v;
        });
        when(lineRepo.saveAll(any())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            Iterable<VoucherLine> lines = (Iterable<VoucherLine>) inv.getArgument(0);
            lines.forEach(savedLines::add);
            return new ArrayList<>(savedLines);
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

        return new Fixture(service, savedVouchers, savedLines);
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
