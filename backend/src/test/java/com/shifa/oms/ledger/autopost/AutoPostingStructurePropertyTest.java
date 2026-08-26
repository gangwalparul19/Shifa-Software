package com.shifa.oms.ledger.autopost;

import com.shifa.oms.finance.Expense;
import com.shifa.oms.finance.ExpenseRepository;
import com.shifa.oms.ledger.AccountGroup;
import com.shifa.oms.ledger.AccountGroupRepository;
import com.shifa.oms.ledger.LedgerAccount;
import com.shifa.oms.ledger.LedgerAccountRepository;
import com.shifa.oms.ledger.domain.AccountNature;
import com.shifa.oms.ledger.domain.DoubleEntry;
import com.shifa.oms.ledger.domain.DraftVoucher;
import com.shifa.oms.ledger.domain.PostingLine;
import com.shifa.oms.ledger.domain.VoucherType;
import com.shifa.oms.order.OrderEntity;
import com.shifa.oms.order.OrderLineItem;
import com.shifa.oms.order.OrderRepository;
import com.shifa.oms.order.OrderSource;
import com.shifa.oms.order.domain.PaymentStatus;
import com.shifa.oms.procurement.PurchaseOrder;
import com.shifa.oms.procurement.PurchaseOrderRepository;
import com.shifa.oms.settings.AppSettings;
import com.shifa.oms.settings.AppSettingsRepository;
import com.shifa.oms.settings.SettingsService;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Property-based test for {@link LedgerAutoPostingService#buildDraft(SourceType, Long)} — auto-derived
 * vouchers are balanced and structurally correct (General Ledger, design Correctness Property 16).
 *
 * <p>Feature: general-ledger-accounting, Property 16: Auto-derived vouchers are balanced and
 * structurally correct.
 *
 * <p>Property statement: for any source business document, the {@link DraftVoucher} produced by
 * {@link LedgerAutoPostingService#buildDraft} is a valid balanced double-entry (>= 2 lines, each line
 * carries exactly one strictly-positive side, and debit total == credit total) and posts to the
 * correct control accounts for the source type:
 * <ul>
 *   <li>ORDER -> a {@link VoucherType#SALES} voucher that credits {@link ControlAccount#SALES}
 *       (and, when tax is present, {@link ControlAccount#GST_OUTPUT}) and debits
 *       {@link ControlAccount#CASH} (fully paid) or {@link ControlAccount#SUNDRY_DEBTORS}
 *       (credit sale);</li>
 *   <li>PURCHASE_ORDER -> a {@link VoucherType#PURCHASE} voucher that debits
 *       {@link ControlAccount#PURCHASES} and credits {@link ControlAccount#SUNDRY_CREDITORS};</li>
 *   <li>EXPENSE -> a {@link VoucherType#PAYMENT} voucher that debits
 *       {@link ControlAccount#DEFAULT_EXPENSE} and credits {@link ControlAccount#CASH};</li>
 *   <li>PAYMENT -> a {@link VoucherType#RECEIPT} voucher that debits {@link ControlAccount#CASH} and
 *       credits {@link ControlAccount#SUNDRY_DEBTORS}.</li>
 * </ul>
 *
 * <p><b>Validates: Requirements 8.1, 9.1, 10.1, 10.4, 11.1, 11.2</b>
 *
 * <p>This is a <em>service-level</em> property test that drives the real
 * {@link LedgerAutoPostingService}. Per the project's Java 25 gotcha (Mockito cannot mock concrete
 * classes) only the Spring Data repository <em>interfaces</em> ({@link OrderRepository},
 * {@link PurchaseOrderRepository}, {@link ExpenseRepository}, {@link LedgerAccountRepository},
 * {@link AccountGroupRepository}) are Mockito-mocked; a REAL {@link ControlAccountResolver} is built
 * over the mocked {@link LedgerAccountRepository}, and the concrete {@link SettingsService} is a
 * recording subclass returning a fixed seller state. Every {@link ControlAccount} key resolves to a
 * distinct {@link LedgerAccount} under a group of an appropriate nature, so the derived vouchers'
 * ledger references can be reverse-mapped back to their control roles for the structural assertions.
 * The pure {@link DoubleEntry#validate(DraftVoucher)} is used as the balanced/structural oracle.
 */
class AutoPostingStructurePropertyTest {

    /** The seller's state; ORDER scenarios post intra- or inter-state relative to this. */
    private static final String SELLER_STATE = "Madhya Pradesh";
    private static final String OTHER_STATE = "Maharashtra";

    /** A fixed source-document id used per scenario (a single document is derived per invocation). */
    private static final Long SOURCE_ID = 777L;

    /** Ledger id assigned to each control account = base + ordinal; groups = group base + ordinal. */
    private static final long LEDGER_ID_BASE = 1000L;
    private static final long GROUP_ID_BASE = 2000L;

    // ---------------------------------------------------------------------------------------------
    // Feature: general-ledger-accounting, Property 16: Auto-derived vouchers are balanced and
    // structurally correct
    // **Validates: Requirements 8.1, 9.1, 10.1, 10.4, 11.1, 11.2**
    // ---------------------------------------------------------------------------------------------

    @Property(tries = 200)
    void autoDerivedVouchersAreBalancedAndStructurallyCorrect(@ForAll("scenarios") Scenario scenario) {
        Fixture f = newFixture(scenario);

        DraftVoucher draft = f.service.buildDraft(scenario.type(), scenario.sourceId());

        // (a) The draft is a valid balanced double-entry per the pure oracle.
        DoubleEntry.Result result = DoubleEntry.validate(draft);
        assertThat(result.ok())
                .as("auto-derived %s voucher must validate as a balanced double-entry (violations: %s)",
                        scenario.type(), result.violations())
                .isTrue();

        // >= 2 lines, each with exactly one strictly-positive side, and debits == credits.
        assertThat(draft.lines().size())
                .as("a voucher must have at least two lines")
                .isGreaterThanOrEqualTo(2);
        for (PostingLine line : draft.lines()) {
            boolean debit = line.debitAmount().signum() > 0;
            boolean credit = line.creditAmount().signum() > 0;
            assertThat(debit ^ credit)
                    .as("each line carries exactly one strictly-positive side (line ledger %s)", line.ledgerId())
                    .isTrue();
        }
        assertThat(result.balance().debitTotal())
                .as("debit total equals credit total")
                .usingComparator(BigDecimal::compareTo)
                .isEqualTo(result.balance().creditTotal());

        // (b) The draft posts to the correct control accounts / voucher type for its source type.
        List<ControlAccount> debits = controlsOnSide(draft, true);
        List<ControlAccount> credits = controlsOnSide(draft, false);
        switch (scenario.type()) {
            case ORDER -> {
                assertThat(draft.type()).as("sales voucher type").isEqualTo(VoucherType.SALES);
                ControlAccount expectedDebit = scenario.salesFullyPaid()
                        ? ControlAccount.CASH : ControlAccount.SUNDRY_DEBTORS;
                assertThat(debits)
                        .as("sales debits exactly the settlement/receivable control for the paid state")
                        .containsExactly(expectedDebit);
                assertThat(credits)
                        .as("sales credits Sales (and only optionally GST Output)")
                        .contains(ControlAccount.SALES)
                        .isSubsetOf(ControlAccount.SALES, ControlAccount.GST_OUTPUT);
            }
            case PURCHASE_ORDER -> {
                assertThat(draft.type()).as("purchase voucher type").isEqualTo(VoucherType.PURCHASE);
                assertThat(debits)
                        .as("purchase debits Purchases")
                        .containsExactly(ControlAccount.PURCHASES);
                assertThat(credits)
                        .as("purchase credits Sundry Creditors")
                        .containsExactly(ControlAccount.SUNDRY_CREDITORS);
            }
            case EXPENSE -> {
                assertThat(draft.type()).as("expense voucher type").isEqualTo(VoucherType.PAYMENT);
                assertThat(debits)
                        .as("expense debits the default expense ledger")
                        .containsExactly(ControlAccount.DEFAULT_EXPENSE);
                assertThat(credits)
                        .as("expense credits Cash")
                        .containsExactly(ControlAccount.CASH);
            }
            case PAYMENT -> {
                assertThat(draft.type()).as("receipt voucher type").isEqualTo(VoucherType.RECEIPT);
                assertThat(debits)
                        .as("customer receipt debits Cash")
                        .containsExactly(ControlAccount.CASH);
                assertThat(credits)
                        .as("customer receipt credits Sundry Debtors")
                        .containsExactly(ControlAccount.SUNDRY_DEBTORS);
            }
        }
    }

    /** The control accounts referenced by lines on the requested side (true = debit, false = credit). */
    private static List<ControlAccount> controlsOnSide(DraftVoucher draft, boolean debitSide) {
        List<ControlAccount> result = new ArrayList<>();
        for (PostingLine line : draft.lines()) {
            boolean isDebit = line.debitAmount().signum() > 0;
            if (isDebit == debitSide) {
                result.add(controlForLedgerId(line.ledgerId()));
            }
        }
        return result;
    }

    /** Reverse-map a ledger id back to the control role it was seeded for. */
    private static ControlAccount controlForLedgerId(Long ledgerId) {
        int ordinal = (int) (ledgerId - LEDGER_ID_BASE);
        return ControlAccount.values()[ordinal];
    }

    // --- Generators ------------------------------------------------------------------------------

    @Provide
    Arbitrary<Scenario> scenarios() {
        return Arbitraries.oneOf(orderScenarios(), purchaseScenarios(), expenseScenarios(), paymentScenarios());
    }

    private Arbitrary<Scenario> orderScenarios() {
        Arbitrary<List<LineSpec>> lines = lineSpec().list().ofMinSize(1).ofMaxSize(5);
        Arbitrary<Boolean> fullyPaid = Arbitraries.of(Boolean.TRUE, Boolean.FALSE);
        Arbitrary<Boolean> intraState = Arbitraries.of(Boolean.TRUE, Boolean.FALSE);
        return Combinators.combine(lines, fullyPaid, intraState).as((ls, fp, intra) -> {
            OrderEntity order = new OrderEntity("SHR-ORD-" + SOURCE_ID, OrderSource.SALESPERSON, 1L,
                    "Customer", "9999999999", "Address line", "City",
                    intra ? SELLER_STATE : OTHER_STATE, "452001");
            BigDecimal total = BigDecimal.ZERO.setScale(2);
            for (LineSpec l : ls) {
                order.addLineItem(new OrderLineItem(1L, "Product", "3004", l.gstRate(),
                        l.qty(), l.lineTotal(), l.lineTotal()));
                total = total.add(l.lineTotal());
            }
            if (fp) {
                order.applyAmounts(total, total, BigDecimal.ZERO.setScale(2),
                        BigDecimal.ZERO.setScale(2), PaymentStatus.FULLY_PAID);
            } else {
                // A strictly-positive remaining amount classifies the order as a credit sale.
                order.applyAmounts(total, BigDecimal.ZERO.setScale(2), BigDecimal.ONE.setScale(2),
                        BigDecimal.ONE.setScale(2), PaymentStatus.COD);
            }
            setId(order, SOURCE_ID);
            return Scenario.order(order, fp);
        });
    }

    private Arbitrary<Scenario> paymentScenarios() {
        return money(100L, 1_000_000L).map(received -> {
            OrderEntity order = new OrderEntity("SHR-PAY-" + SOURCE_ID, OrderSource.SALESPERSON, 1L,
                    "Customer", "9999999999", "Address line", "City", SELLER_STATE, "452001");
            order.applyAmounts(received, received, BigDecimal.ZERO.setScale(2),
                    BigDecimal.ZERO.setScale(2), PaymentStatus.FULLY_PAID);
            setId(order, SOURCE_ID);
            return Scenario.payment(order);
        });
    }

    private Arbitrary<Scenario> purchaseScenarios() {
        return money(100L, 10_000_000L).map(total -> {
            PurchaseOrder po = new PurchaseOrder("PO-" + SOURCE_ID, 1L, "notes", 1L);
            writeField(po, "totalAmount", total);
            setId(po, SOURCE_ID);
            return Scenario.purchase(po);
        });
    }

    private Arbitrary<Scenario> expenseScenarios() {
        return money(100L, 10_000_000L).map(amount -> {
            Expense expense = new Expense("Rent", "Monthly rent", amount, LocalDate.of(2024, 6, 1), 1L);
            setId(expense, SOURCE_ID);
            return Scenario.expense(expense);
        });
    }

    private Arbitrary<LineSpec> lineSpec() {
        Arbitrary<BigDecimal> gstRate = Arbitraries.of("0", "5", "12", "18").map(BigDecimal::new);
        Arbitrary<Integer> qty = Arbitraries.integers().between(1, 10);
        Arbitrary<BigDecimal> lineTotal = money(100L, 1_000_000L);
        return Combinators.combine(gstRate, qty, lineTotal).as(LineSpec::new);
    }

    /** A strictly-positive scale-2 rupee amount, generated from a cents range. */
    private Arbitrary<BigDecimal> money(long minCents, long maxCents) {
        return Arbitraries.longs().between(minCents, maxCents).map(c -> BigDecimal.valueOf(c, 2));
    }

    /** One generated order line: a GST rate percent, a quantity, and a GST-inclusive line total. */
    record LineSpec(BigDecimal gstRate, int qty, BigDecimal lineTotal) {
    }

    /**
     * A generated source scenario: the source type + id and exactly one of the built aggregates
     * (order for ORDER/PAYMENT, purchase order for PURCHASE_ORDER, expense for EXPENSE).
     */
    record Scenario(SourceType type, Long sourceId, OrderEntity order, PurchaseOrder purchaseOrder,
                    Expense expense, boolean salesFullyPaid) {

        static Scenario order(OrderEntity order, boolean fullyPaid) {
            return new Scenario(SourceType.ORDER, SOURCE_ID, order, null, null, fullyPaid);
        }

        static Scenario payment(OrderEntity order) {
            return new Scenario(SourceType.PAYMENT, SOURCE_ID, order, null, null, false);
        }

        static Scenario purchase(PurchaseOrder po) {
            return new Scenario(SourceType.PURCHASE_ORDER, SOURCE_ID, null, po, null, false);
        }

        static Scenario expense(Expense expense) {
            return new Scenario(SourceType.EXPENSE, SOURCE_ID, null, null, expense, false);
        }
    }

    // --- Fixture: LedgerAutoPostingService over mocked repositories + real collaborators ----------

    private record Fixture(LedgerAutoPostingService service) {
    }

    private static Fixture newFixture(Scenario scenario) {
        OrderRepository orderRepo = mock(OrderRepository.class);
        PurchaseOrderRepository purchaseRepo = mock(PurchaseOrderRepository.class);
        ExpenseRepository expenseRepo = mock(ExpenseRepository.class);
        LedgerAccountRepository ledgerRepo = mock(LedgerAccountRepository.class);
        AccountGroupRepository groupRepo = mock(AccountGroupRepository.class);

        // Seed a distinct control ledger (under a group of an appropriate nature) per control role.
        Map<Long, AccountGroup> groups = new HashMap<>();
        for (ControlAccount control : ControlAccount.values()) {
            int ordinal = control.ordinal();
            long ledgerId = LEDGER_ID_BASE + ordinal;
            long groupId = GROUP_ID_BASE + ordinal;

            AccountGroup group = new AccountGroup(control.name() + " group", natureFor(control), null, true);
            setId(group, groupId);
            groups.put(groupId, group);

            LedgerAccount ledger = new LedgerAccount(control.name(), groupId, control.key(), true);
            setId(ledger, ledgerId);

            when(ledgerRepo.findByControlKey(control.key())).thenReturn(Optional.of(ledger));
        }
        when(groupRepo.findById(anyLong()))
                .thenAnswer(inv -> Optional.ofNullable(groups.get(inv.<Long>getArgument(0))));

        // Return the built source aggregate by id from the relevant repository.
        if (scenario.order() != null) {
            when(orderRepo.findById(scenario.sourceId())).thenReturn(Optional.of(scenario.order()));
        }
        if (scenario.purchaseOrder() != null) {
            when(purchaseRepo.findById(scenario.sourceId())).thenReturn(Optional.of(scenario.purchaseOrder()));
        }
        if (scenario.expense() != null) {
            when(expenseRepo.findById(scenario.sourceId())).thenReturn(Optional.of(scenario.expense()));
        }

        ControlAccountResolver resolver = new ControlAccountResolver(ledgerRepo);
        SettingsService settingsService = new FixedSettingsService();
        LedgerAutoPostingService service = new LedgerAutoPostingService(
                orderRepo, purchaseRepo, expenseRepo, resolver, groupRepo, settingsService);
        return new Fixture(service);
    }

    /** A realistic account nature for each control role (nature is derived from the owning group). */
    private static AccountNature natureFor(ControlAccount control) {
        return switch (control) {
            case SUNDRY_DEBTORS, GST_INPUT, CASH, BANK -> AccountNature.ASSET;
            case SUNDRY_CREDITORS, GST_OUTPUT -> AccountNature.LIABILITY;
            case SALES -> AccountNature.INCOME;
            case PURCHASES, DEFAULT_EXPENSE -> AccountNature.EXPENSE;
        };
    }

    /** A recording {@link SettingsService} subclass returning a fixed seller state (Java 25 gotcha). */
    private static final class FixedSettingsService extends SettingsService {
        private final AppSettings settings;

        FixedSettingsService() {
            super(mock(AppSettingsRepository.class));
            this.settings = AppSettings.defaults();
            this.settings.setState(SELLER_STATE);
        }

        @Override
        public AppSettings getSettings() {
            return settings;
        }
    }

    // --- id / field reflection helpers (JPA-generated ids have no public setter) ------------------

    private static void setId(Object entity, Long id) {
        writeField(entity, "id", id);
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
