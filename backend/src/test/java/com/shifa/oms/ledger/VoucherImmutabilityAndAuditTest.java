package com.shifa.oms.ledger;

import com.shifa.oms.audit.AuditActions;
import com.shifa.oms.audit.AuditEvent;
import com.shifa.oms.audit.AuditEventRepository;
import com.shifa.oms.audit.AuditService;
import com.shifa.oms.auth.AuthPrincipal;
import com.shifa.oms.auth.CurrentUserService;
import com.shifa.oms.auth.Role;
import com.shifa.oms.ledger.VoucherService.PostVoucherCommand;
import com.shifa.oms.ledger.VoucherService.VoucherLineCommand;
import com.shifa.oms.ledger.domain.AccountNature;
import com.shifa.oms.ledger.domain.DrCr;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Example-based unit tests for posted-voucher immutability, bidirectional reversal linking, and the
 * statutory audit trail on the General Ledger (Reqs 6.1, 6.2, 6.4, 15.1–15.4).
 *
 * <p>These complement the property tests around {@link VoucherService}: they pin down, with concrete
 * examples and API-shape assertions, that
 * <ul>
 *   <li><b>6.1 / 6.2</b> — a posted voucher cannot be mutated: the {@link Voucher} (and
 *       {@link VoucherLine}) entities expose <em>no setter</em> for any financial field
 *       (type / date / financial year / reference / narration / amounts), the <em>only</em>
 *       post-creation mutator on {@link Voucher} is {@link Voucher#markReversedBy(Long)} (the
 *       reversal link, Req 6.4), and {@link VoucherService} declares <em>no update or delete</em>
 *       operation — only {@code post} and {@code reverse};</li>
 *   <li><b>6.4</b> — reversing a voucher wires the two vouchers together in both directions
 *       (reversing {@code -> reverses ->} original, original {@code -> reversedBy ->} reversing);</li>
 *   <li><b>15.1–15.4</b> — posting records a {@code VOUCHER_POSTED} event and reversing records a
 *       {@code VOUCHER_REVERSED} event, each capturing the acting user, the action, the voucher
 *       reference(s) and a timestamp, retained (the audit service offers no edit/delete of events)
 *       and retrievable for a voucher in chronological order.</li>
 * </ul>
 *
 * <p>Per the project's Java 25 gotcha (Mockito cannot mock concrete classes), only the Spring Data
 * repository <em>interfaces</em> are mocked; the concrete collaborators are built as REAL instances
 * over those mocks — a real {@link FinancialYearService}, {@link VoucherReferenceSequencer},
 * {@link CurrentUserService}, and a real {@link AuditService} over a mocked
 * {@link AuditEventRepository} that <em>captures</em> the saved {@link AuditEvent}s (in save order)
 * so the recorded action / reference / actor / order can be asserted. This mirrors the fixture used
 * by {@link ClosedFinancialYearPostingPropertyTest}.
 */
class VoucherImmutabilityAndAuditTest {

    private static final Long FY_ID = 1L;
    private static final Long DEBIT_LEDGER_ID = 100L;
    private static final Long CREDIT_LEDGER_ID = 101L;

    private static final String ACTING_USER = "accountant";
    private static final Long ACTING_USER_ID = 42L;

    private Fixture fixture;

    @BeforeEach
    void authenticateAndBuildFixture() {
        // An authenticated finance principal so the recorded audit events (and posted_by) capture the
        // acting user (Reqs 15.1, 15.2).
        AuthPrincipal principal = new AuthPrincipal(ACTING_USER_ID, ACTING_USER, Role.ACCOUNTANT);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                principal, "n/a", AuthorityUtils.createAuthorityList("ROLE_ACCOUNTANT")));
        fixture = newFixture();
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // =============================================================================================
    // Req 6.1 / 6.2 — a posted voucher is immutable (no update/delete path can mutate it)
    // =============================================================================================

    @Test
    @DisplayName("6.1/6.2: Voucher exposes no setter for any financial field")
    void voucherExposesNoFinancialFieldSetters() {
        // The financial fields a correction must never mutate on a posted voucher.
        List<String> financialFields = List.of(
                "voucherType", "voucherDate", "financialYearId", "voucherReference", "narration",
                "sourceType", "sourceId", "posted");

        for (String field : financialFields) {
            assertThat(hasSetter(Voucher.class, field))
                    .as("Voucher must expose no setter for financial field '%s' (Reqs 6.1, 6.2)", field)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("6.1/6.2: VoucherLine exposes no setter for its amounts")
    void voucherLineExposesNoAmountSetters() {
        for (String field : List.of("debit", "credit", "voucherId", "ledgerAccountId", "lineOrder")) {
            assertThat(hasSetter(VoucherLine.class, field))
                    .as("VoucherLine must expose no setter for '%s' (Reqs 6.1, 6.2)", field)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("6.1/6.4: markReversedBy is the only public mutator on Voucher")
    void markReversedByIsTheOnlyMutatorOnVoucher() {
        List<String> mutators = new ArrayList<>();
        for (Method method : Voucher.class.getDeclaredMethods()) {
            if (!Modifier.isPublic(method.getModifiers()) || method.isSynthetic()) {
                continue;
            }
            String name = method.getName();
            boolean accessor = name.startsWith("get") || name.startsWith("is");
            if (!accessor) {
                mutators.add(name);
            }
        }
        // The only permitted post-creation mutation is linking the original to its reversal (Req 6.4).
        assertThat(mutators)
                .as("markReversedBy must be the sole non-accessor public method on Voucher (Reqs 6.1, 6.4)")
                .containsExactly("markReversedBy");
        // And there is categorically no JavaBean-style setter.
        assertThat(mutators).noneMatch(name -> name.startsWith("set"));
    }

    @Test
    @DisplayName("6.2: VoucherService declares only post/postForSource/reverse — no update or delete operation")
    void voucherServiceHasNoUpdateOrDeleteOperation() {
        List<String> publicApi = new ArrayList<>();
        for (Method method : VoucherService.class.getDeclaredMethods()) {
            if (Modifier.isPublic(method.getModifiers()) && !method.isSynthetic()) {
                publicApi.add(method.getName());
            }
        }

        assertThat(publicApi)
                .as("VoucherService's only mutating operations are post, postForSource and reverse — "
                        + "all additive posting paths that create/post or reverse a voucher; none updates "
                        + "or deletes a posted voucher (Req 6.2)")
                .containsExactlyInAnyOrder("post", "reverse", "postForSource");

        // Defensive: no correction verb that would edit or remove a posted voucher exists.
        assertThat(publicApi).noneMatch(name -> {
            String n = name.toLowerCase(Locale.ROOT);
            return n.contains("update") || n.contains("delete") || n.contains("edit")
                    || n.contains("modify") || n.contains("remove");
        });
    }

    // =============================================================================================
    // Req 6.4 — reversal links are bidirectional
    // =============================================================================================

    @Test
    @DisplayName("6.4: reversal links the reversing and original vouchers in both directions")
    void reversalLinksAreBidirectional() {
        Voucher original = fixture.service.post(journal("Original entry"));
        assertThat(original.getReversedByVoucherId()).as("a fresh voucher is not yet reversed").isNull();

        Voucher reversing = fixture.service.reverse(original.getId());

        // reversing -> reverses -> original
        assertThat(reversing.getReversesVoucherId())
                .as("the reversing voucher points back to the original (Req 6.4)")
                .isEqualTo(original.getId());
        // original -> reversedBy -> reversing
        assertThat(fixture.byId(original.getId()).getReversedByVoucherId())
                .as("the original voucher points forward to its reversal (Req 6.4)")
                .isEqualTo(reversing.getId());
    }

    // =============================================================================================
    // Req 15.1 — posting records a VOUCHER_POSTED audit event (user, action, reference, timestamp)
    // =============================================================================================

    @Test
    @DisplayName("15.1: posting records a VOUCHER_POSTED audit event capturing user, action, reference, timestamp")
    void postingRecordsVoucherPostedAuditEvent() {
        Voucher posted = fixture.service.post(journal("Sale of goods"));

        AuditEvent event = fixture.singleEventFor(AuditActions.VOUCHER_POSTED);
        assertThat(event.getAction()).isEqualTo(AuditActions.VOUCHER_POSTED);
        assertThat(event.getEntityType()).isEqualTo(AuditActions.ENTITY_VOUCHER);
        assertThat(event.getEntityId()).isEqualTo(String.valueOf(posted.getId()));
        // the acting user
        assertThat(event.getActorUsername()).isEqualTo(ACTING_USER);
        assertThat(event.getActorUserId()).isEqualTo(ACTING_USER_ID);
        // the voucher reference
        assertThat(event.getSummary()).contains(posted.getVoucherReference());
        // the timestamp
        assertThat(event.getCreatedAt()).as("the audit event carries a timestamp (Req 15.1)").isNotNull();
    }

    // =============================================================================================
    // Req 15.2 — reversing records a VOUCHER_REVERSED event capturing BOTH references + user
    // =============================================================================================

    @Test
    @DisplayName("15.2: reversing records a VOUCHER_REVERSED event capturing both references and the user")
    void reversingRecordsVoucherReversedAuditEvent() {
        Voucher original = fixture.service.post(journal("Original entry"));
        Voucher reversing = fixture.service.reverse(original.getId());

        AuditEvent event = fixture.singleEventFor(AuditActions.VOUCHER_REVERSED);
        assertThat(event.getAction()).isEqualTo(AuditActions.VOUCHER_REVERSED);
        assertThat(event.getEntityType()).isEqualTo(AuditActions.ENTITY_VOUCHER);
        assertThat(event.getActorUsername()).isEqualTo(ACTING_USER);
        // both the original and the reversing voucher references are captured (Req 15.2)
        assertThat(event.getSummary())
                .contains(original.getVoucherReference())
                .contains(reversing.getVoucherReference());
        assertThat(event.getCreatedAt()).isNotNull();
    }

    // =============================================================================================
    // Req 15.3 — audit events are retained: no edit/delete operation is offered
    // =============================================================================================

    @Test
    @DisplayName("15.3: the audit service offers no operation to edit or delete a recorded event")
    void auditServiceOffersNoEditOrDeleteOfEvents() {
        List<String> publicApi = new ArrayList<>();
        for (Method method : AuditService.class.getDeclaredMethods()) {
            if (Modifier.isPublic(method.getModifiers()) && !method.isSynthetic()) {
                publicApi.add(method.getName().toLowerCase(Locale.ROOT));
            }
        }
        // Only record (write) + list (read) are exposed — nothing that edits or deletes the trail.
        assertThat(publicApi)
                .as("AuditService retains events without any edit/delete operation (Req 15.3)")
                .noneMatch(name -> name.contains("update") || name.contains("delete")
                        || name.contains("edit") || name.contains("remove"));
    }

    // =============================================================================================
    // Req 15.4 — the audit events for a voucher are retrievable in chronological order
    // =============================================================================================

    @Test
    @DisplayName("15.4: post-then-reverse audit events are retained in chronological order")
    void auditEventsAreRetrievableInChronologicalOrder() {
        Voucher original = fixture.service.post(journal("Original entry"));
        fixture.service.reverse(original.getId());

        // Events captured in the exact order they were recorded (chronological): posted, then reversed.
        assertThat(fixture.recordedActions())
                .as("the voucher's audit events are retained in chronological order (Reqs 15.3, 15.4)")
                .containsExactly(AuditActions.VOUCHER_POSTED, AuditActions.VOUCHER_REVERSED);
    }

    // --- Command builder -------------------------------------------------------------------------

    /** A balanced two-line JOURNAL voucher for today with the given narration. */
    private static PostVoucherCommand journal(String narration) {
        BigDecimal amount = new BigDecimal("125.00");
        List<VoucherLineCommand> lines = List.of(
                new VoucherLineCommand(DEBIT_LEDGER_ID, DrCr.DEBIT, amount),
                new VoucherLineCommand(CREDIT_LEDGER_ID, DrCr.CREDIT, amount));
        return new PostVoucherCommand("JOURNAL", LocalDate.of(2025, 6, 15), narration, lines);
    }

    // --- reflection helper -----------------------------------------------------------------------

    /** True when {@code type} declares a public {@code set<Field>(...)} method. */
    private static boolean hasSetter(Class<?> type, String fieldName) {
        String setter = "set" + Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);
        for (Method method : type.getDeclaredMethods()) {
            if (method.getName().equals(setter) && Modifier.isPublic(method.getModifiers())) {
                return true;
            }
        }
        return false;
    }

    // --- Fixture: VoucherService over mocked repository interfaces + real collaborators ----------

    private static final class Fixture {
        final VoucherService service;
        final Map<Long, Voucher> voucherStore;
        final List<AuditEvent> auditEvents;

        Fixture(VoucherService service, Map<Long, Voucher> voucherStore, List<AuditEvent> auditEvents) {
            this.service = service;
            this.voucherStore = voucherStore;
            this.auditEvents = auditEvents;
        }

        Voucher byId(Long id) {
            return voucherStore.get(id);
        }

        List<String> recordedActions() {
            List<String> actions = new ArrayList<>();
            for (AuditEvent event : auditEvents) {
                actions.add(event.getAction());
            }
            return actions;
        }

        AuditEvent singleEventFor(String action) {
            List<AuditEvent> matching = new ArrayList<>();
            for (AuditEvent event : auditEvents) {
                if (action.equals(event.getAction())) {
                    matching.add(event);
                }
            }
            assertThat(matching).as("exactly one %s event was recorded", action).hasSize(1);
            return matching.get(0);
        }
    }

    private static Fixture newFixture() {
        VoucherRepository voucherRepo = mock(VoucherRepository.class);
        VoucherLineRepository lineRepo = mock(VoucherLineRepository.class);
        LedgerAccountRepository ledgerRepo = mock(LedgerAccountRepository.class);
        AccountGroupRepository groupRepo = mock(AccountGroupRepository.class);
        FinancialYearRepository fyRepo = mock(FinancialYearRepository.class);
        OpeningBalanceRepository obRepo = mock(OpeningBalanceRepository.class);
        LedgerVoucherSequenceRepository seqRepo = mock(LedgerVoucherSequenceRepository.class);
        AuditEventRepository auditRepo = mock(AuditEventRepository.class);

        // --- An open financial year that contains the voucher date (posting is allowed).
        FinancialYearService.FinancialYearWindow window =
                FinancialYearService.windowFor(LocalDate.of(2025, 4, 1));
        FinancialYear fy = new FinancialYear(window.startDate(), window.endDate(), window.label());
        setId(fy, FY_ID);
        fy.setClosed(false);
        when(fyRepo.findFirstByStartDateLessThanEqualAndEndDateGreaterThanEqual(any(), any()))
                .thenReturn(Optional.of(fy));
        when(fyRepo.findById(FY_ID)).thenReturn(Optional.of(fy));
        when(fyRepo.findByStartDate(any())).thenReturn(Optional.of(fy));

        // --- Two valid ledgers, each under a group carrying a nature (nature is irrelevant to these
        //     assertions, so any pair is fine).
        Map<Long, LedgerAccount> ledgers = new HashMap<>();
        Map<Long, AccountGroup> groups = new HashMap<>();
        registerLedger(ledgers, groups, DEBIT_LEDGER_ID, 200L, AccountNature.ASSET);
        registerLedger(ledgers, groups, CREDIT_LEDGER_ID, 201L, AccountNature.INCOME);
        when(ledgerRepo.findById(anyLong()))
                .thenAnswer(inv -> Optional.ofNullable(ledgers.get(inv.<Long>getArgument(0))));
        when(groupRepo.findById(anyLong()))
                .thenAnswer(inv -> Optional.ofNullable(groups.get(inv.<Long>getArgument(0))));

        // --- Stateful voucher/line persistence so reverse() can re-load the original + its lines.
        Map<Long, Voucher> voucherStore = new HashMap<>();
        List<VoucherLine> allLines = new ArrayList<>();
        AtomicLong voucherSeq = new AtomicLong(500L);
        when(voucherRepo.save(any(Voucher.class))).thenAnswer(inv -> {
            Voucher v = inv.getArgument(0);
            if (readId(v) == null) {
                setId(v, voucherSeq.incrementAndGet());
            }
            voucherStore.put(readId(v), v);
            return v;
        });
        when(voucherRepo.findById(anyLong()))
                .thenAnswer(inv -> Optional.ofNullable(voucherStore.get(inv.<Long>getArgument(0))));
        when(lineRepo.saveAll(any())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            Iterable<VoucherLine> lines = (Iterable<VoucherLine>) inv.getArgument(0);
            List<VoucherLine> saved = new ArrayList<>();
            lines.forEach(line -> {
                allLines.add(line);
                saved.add(line);
            });
            return saved;
        });
        when(lineRepo.findByVoucherIdOrderByLineOrderAsc(anyLong())).thenAnswer(inv -> {
            Long voucherId = inv.getArgument(0);
            List<VoucherLine> forVoucher = new ArrayList<>();
            for (VoucherLine line : allLines) {
                if (voucherId.equals(line.getVoucherId())) {
                    forVoucher.add(line);
                }
            }
            forVoucher.sort((a, b) -> Integer.compare(a.getLineOrder(), b.getLineOrder()));
            return forVoucher;
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

        // --- Audit repository CAPTURES saved events in save order, simulating the @PrePersist
        //     timestamp (a mock does not fire JPA lifecycle callbacks) so we can assert the trail.
        List<AuditEvent> auditEvents = new ArrayList<>();
        when(auditRepo.save(any(AuditEvent.class))).thenAnswer(inv -> {
            AuditEvent event = inv.getArgument(0);
            if (readCreatedAt(event) == null) {
                setCreatedAt(event, java.time.LocalDateTime.now());
            }
            auditEvents.add(event);
            return event;
        });

        // --- Real concrete collaborators over the mocked interfaces (Java 25: no mocking concretes).
        CurrentUserService currentUserService = new CurrentUserService();
        FinancialYearService financialYearService = new FinancialYearService(
                fyRepo, obRepo, voucherRepo, lineRepo, ledgerRepo, groupRepo, currentUserService);
        VoucherReferenceSequencer sequencer = new VoucherReferenceSequencer(seqRepo);
        AuditService auditService = new AuditService(auditRepo, currentUserService);

        VoucherService service = new VoucherService(voucherRepo, lineRepo, ledgerRepo, groupRepo,
                financialYearService, sequencer, currentUserService, auditService);

        return new Fixture(service, voucherStore, auditEvents);
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

    // --- reflection helpers (JPA-generated fields have no public setter) --------------------------

    private static Long readId(Object entity) {
        return (Long) readField(entity, "id");
    }

    private static void setId(Object entity, Long id) {
        writeField(entity, "id", id);
    }

    private static java.time.LocalDateTime readCreatedAt(Object entity) {
        return (java.time.LocalDateTime) readField(entity, "createdAt");
    }

    private static void setCreatedAt(Object entity, java.time.LocalDateTime value) {
        writeField(entity, "createdAt", value);
    }

    private static Object readField(Object entity, String fieldName) {
        try {
            Field field = entity.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.get(entity);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unable to read '" + fieldName + "' on " + entity.getClass(), e);
        }
    }

    private static void writeField(Object entity, String fieldName, Object value) {
        try {
            Field field = entity.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(entity, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unable to write '" + fieldName + "' on " + entity.getClass(), e);
        }
    }
}
