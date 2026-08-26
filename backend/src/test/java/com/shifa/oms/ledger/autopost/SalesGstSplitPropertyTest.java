package com.shifa.oms.ledger.autopost;

import com.shifa.oms.finance.ExpenseRepository;
import com.shifa.oms.gst.domain.GstEngine;
import com.shifa.oms.gst.domain.GstEngine.GstLine;
import com.shifa.oms.gst.domain.GstEngine.TaxSplit;
import com.shifa.oms.gst.domain.SupplyType;
import com.shifa.oms.ledger.AccountGroup;
import com.shifa.oms.ledger.AccountGroupRepository;
import com.shifa.oms.ledger.LedgerAccount;
import com.shifa.oms.ledger.LedgerAccountRepository;
import com.shifa.oms.ledger.domain.AccountNature;
import com.shifa.oms.ledger.domain.DraftVoucher;
import com.shifa.oms.ledger.domain.PostingLine;
import com.shifa.oms.ledger.domain.VoucherType;
import com.shifa.oms.order.OrderEntity;
import com.shifa.oms.order.OrderLineItem;
import com.shifa.oms.order.OrderRepository;
import com.shifa.oms.order.OrderSource;
import com.shifa.oms.order.domain.PaymentStatus;
import com.shifa.oms.procurement.PurchaseOrderRepository;
import com.shifa.oms.settings.AppSettings;
import com.shifa.oms.settings.SettingsService;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Property-based test for the Sales auto-posting GST split (General Ledger, design Correctness
 * Property 17).
 *
 * <p>Feature: general-ledger-accounting, Property 17: Sales voucher GST split reconciles.
 *
 * <p><b>Validates: Requirements 8.2</b>
 *
 * <p>For any sales order with N GST-inclusive line items (varied HSN, GST rate in {0, 5, 18},
 * quantity, and line total) and a place-of-supply state, the {@code Sales} {@link DraftVoucher} that
 * {@link LedgerAutoPostingService#buildDraft} derives from it credits the {@code GST_OUTPUT} control
 * ledger with the GST component and the {@code SALES} control ledger with the net-of-GST value, and
 * {@code net + gst} equals the order gross debited to Cash / Sundry Debtors — which itself equals the
 * sum of the GST-inclusive line totals. The expected net/GST are computed independently through the
 * same pure {@link GstEngine#splitLine} oracle (taxable = total / (1 + rate/100)) and the intra /
 * inter-state classification the CA GST dashboard uses. The derived voucher is also asserted balanced.
 *
 * <p>Per the project's Java 25 gotcha (Mockito cannot mock concrete classes), only the Spring Data
 * repository <em>interfaces</em> ({@link OrderRepository}, {@link LedgerAccountRepository},
 * {@link AccountGroupRepository}) are mocked; the {@link ControlAccountResolver} is a REAL instance
 * over the mocked ledger repository, and {@link SettingsService} is a recording subclass returning a
 * fixed seller state so intra vs inter-state supply is deterministic.
 */
class SalesGstSplitPropertyTest {

    /** Codebase-wide money scale ({@code BigDecimal} scale-2 {@code HALF_UP}). */
    private static final int MONEY_SCALE = 2;

    /** The fixed seller state; orders in this state are intra-state (CGST+SGST), others inter-state (IGST). */
    private static final String SELLER_STATE = "Madhya Pradesh";

    // Distinct control ledgers with appropriate natures (nature is derived from the owning group).
    private static final long SALES_LEDGER_ID = 10L;
    private static final long GST_OUTPUT_LEDGER_ID = 11L;
    private static final long CASH_LEDGER_ID = 12L;
    private static final long SUNDRY_DEBTORS_LEDGER_ID = 13L;

    private static final long ORDER_ID = 7L;

    // ---------------------------------------------------------------------------------------------
    // Feature: general-ledger-accounting, Property 17: Sales voucher GST split reconciles
    // **Validates: Requirements 8.2**
    // ---------------------------------------------------------------------------------------------

    @Property(tries = 200)
    void salesVoucherGstSplitReconciles(@ForAll("orderSpecs") OrderSpec spec) {
        LedgerAutoPostingService service = newService();
        OrderEntity order = spec.toOrder();
        when(orderRepositoryStub.findById(anyLong())).thenReturn(Optional.of(order));

        // --- Independent oracle: split each GST-inclusive line via the pure GstEngine, summing
        //     net (taxable) and GST (total tax) with the same intra/inter-state classification.
        SupplyType supplyType = GstEngine.classify(spec.state, SELLER_STATE);
        BigDecimal expectedNet = scale(BigDecimal.ZERO);
        BigDecimal expectedGst = scale(BigDecimal.ZERO);
        BigDecimal grossLineTotals = scale(BigDecimal.ZERO);
        for (LineSpec line : spec.lines) {
            TaxSplit split = GstEngine.splitLine(
                    new GstLine(line.hsn, line.productName, line.gstRate, line.quantity, line.lineTotal),
                    supplyType);
            expectedNet = expectedNet.add(split.taxable());
            expectedGst = expectedGst.add(split.totalTax());
            grossLineTotals = grossLineTotals.add(scale(line.lineTotal));
        }
        expectedNet = scale(expectedNet);
        expectedGst = scale(expectedGst);
        BigDecimal expectedGross = scale(expectedNet.add(expectedGst));

        // --- Act
        DraftVoucher draft = service.buildDraft(SourceType.ORDER, ORDER_ID);

        assertThat(draft.type()).isEqualTo(VoucherType.SALES);

        // --- The single debit line == the order gross (Cash when fully paid, else Sundry Debtors).
        List<PostingLine> debits = draft.lines().stream().filter(PostingLine::isDebit).toList();
        assertThat(debits).as("a Sales voucher has exactly one debit line (the gross)").hasSize(1);
        PostingLine debit = debits.get(0);
        assertThat(debit.ledgerId())
                .as("gross is debited to Cash (fully paid) or Sundry Debtors (credit sale)")
                .isEqualTo(spec.fullyPaid ? CASH_LEDGER_ID : SUNDRY_DEBTORS_LEDGER_ID);
        assertThat(debit.amount())
                .as("the debit to Cash/Sundry Debtors equals the order gross")
                .isEqualByComparingTo(expectedGross);

        // --- The SALES credit == the net-of-GST value (Req 8.2).
        PostingLine salesCredit = creditFor(draft, SALES_LEDGER_ID);
        assertThat(salesCredit).as("the Sales ledger is credited").isNotNull();
        assertThat(salesCredit.amount())
                .as("the credit to Sales equals the net-of-GST value")
                .isEqualByComparingTo(expectedNet);

        // --- The GST_OUTPUT credit == the GST component; omitted when total tax is zero (Req 8.2).
        PostingLine gstCredit = creditFor(draft, GST_OUTPUT_LEDGER_ID);
        if (expectedGst.signum() > 0) {
            assertThat(gstCredit).as("GST Output is credited when there is tax").isNotNull();
            assertThat(gstCredit.amount())
                    .as("the credit to GST Output equals the GST component")
                    .isEqualByComparingTo(expectedGst);
        } else {
            assertThat(gstCredit).as("no GST Output line when total tax is zero").isNull();
        }

        // --- Reconciliation: net + gst == gross == sum of the GST-inclusive line totals.
        assertThat(expectedGross)
                .as("net + gst reconciles to the sum of the GST-inclusive line totals")
                .isEqualByComparingTo(grossLineTotals);

        // --- The derived voucher is balanced (sum of debits == sum of credits).
        BigDecimal totalDebit = draft.lines().stream()
                .map(PostingLine::debitAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalCredit = draft.lines().stream()
                .map(PostingLine::creditAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(totalDebit)
                .as("the auto-derived Sales voucher is balanced")
                .isEqualByComparingTo(totalCredit);
    }

    // --- Generators ------------------------------------------------------------------------------

    /** One GST-inclusive order line: varied HSN, GST rate in {0, 5, 18}, quantity, and line total. */
    record LineSpec(String hsn, String productName, BigDecimal gstRate, int quantity, BigDecimal lineTotal) {
    }

    /** An order: its place-of-supply state, whether it is fully paid, and its line items. */
    record OrderSpec(String state, boolean fullyPaid, List<LineSpec> lines) {

        OrderEntity toOrder() {
            OrderEntity order = new OrderEntity("SHR-GLTEST-" + ORDER_ID, OrderSource.SALESPERSON, 1L,
                    "Test Customer", "9999999999", "1 Test Street", "Test City", state, "452001");
            BigDecimal gross = BigDecimal.ZERO;
            for (LineSpec line : lines) {
                order.addLineItem(new OrderLineItem(null, line.productName, line.hsn, line.gstRate,
                        line.quantity, line.lineTotal, line.lineTotal));
                gross = gross.add(line.lineTotal);
            }
            gross = gross.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
            BigDecimal received = fullyPaid ? gross : BigDecimal.ZERO.setScale(MONEY_SCALE);
            BigDecimal remaining = gross.subtract(received);
            order.applyAmounts(gross, received, remaining, remaining,
                    fullyPaid ? PaymentStatus.FULLY_PAID : PaymentStatus.COD);
            return order;
        }
    }

    @Provide
    Arbitrary<OrderSpec> orderSpecs() {
        Arbitrary<String> states = Arbitraries.of(
                SELLER_STATE, "Maharashtra", "Delhi", "Karnataka", "Tamil Nadu");
        Arbitrary<Boolean> paidFlags = Arbitraries.of(true, false);
        Arbitrary<List<LineSpec>> lineLists = lineSpecs().list().ofMinSize(1).ofMaxSize(6);
        return Combinators.combine(states, paidFlags, lineLists).as(OrderSpec::new);
    }

    private Arbitrary<LineSpec> lineSpecs() {
        Arbitrary<String> hsns = Arbitraries.of("3004", "1211", "3305", "30049011", "");
        Arbitrary<String> names = Arbitraries.of("Herbal Syrup", "Immunity Booster", "Pain Balm");
        Arbitrary<BigDecimal> gstRates = Arbitraries.of(
                new BigDecimal("0"), new BigDecimal("5"), new BigDecimal("18"));
        Arbitrary<Integer> quantities = Arbitraries.integers().between(1, 20);
        // GST-inclusive line total: a strictly positive 2-dp amount from 0.01 up to 100000.00.
        Arbitrary<BigDecimal> lineTotals = Arbitraries.longs().between(1L, 10_000_000L)
                .map(v -> BigDecimal.valueOf(v).movePointLeft(2));
        return Combinators.combine(hsns, names, gstRates, quantities, lineTotals).as(LineSpec::new);
    }

    // --- Helpers ---------------------------------------------------------------------------------

    private static PostingLine creditFor(DraftVoucher draft, long ledgerId) {
        return draft.lines().stream()
                .filter(l -> l.isCredit() && Long.valueOf(ledgerId).equals(l.ledgerId()))
                .findFirst().orElse(null);
    }

    private static BigDecimal scale(BigDecimal value) {
        return value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    // --- Fixture: LedgerAutoPostingService over mocked interfaces + real collaborators -----------

    /** Holds the per-test mocked order repository so the property can stub the generated order. */
    private OrderRepository orderRepositoryStub;

    /**
     * Build a {@link LedgerAutoPostingService} whose control ledgers (SALES, GST_OUTPUT, CASH,
     * SUNDRY_DEBTORS) resolve to distinct ledgers with appropriate natures, over mocked repository
     * interfaces, a REAL {@link ControlAccountResolver}, and a recording {@link SettingsService}
     * returning the fixed seller state.
     */
    private LedgerAutoPostingService newService() {
        OrderRepository orderRepository = mock(OrderRepository.class);
        this.orderRepositoryStub = orderRepository;
        PurchaseOrderRepository purchaseOrderRepository = mock(PurchaseOrderRepository.class);
        ExpenseRepository expenseRepository = mock(ExpenseRepository.class);
        LedgerAccountRepository ledgerRepo = mock(LedgerAccountRepository.class);
        AccountGroupRepository groupRepo = mock(AccountGroupRepository.class);

        Map<Long, LedgerAccount> ledgers = new HashMap<>();
        Map<Long, AccountGroup> groups = new HashMap<>();
        Map<String, LedgerAccount> byControlKey = new HashMap<>();
        register(ledgers, groups, byControlKey, ControlAccount.SALES, SALES_LEDGER_ID, 110L, AccountNature.INCOME);
        register(ledgers, groups, byControlKey, ControlAccount.GST_OUTPUT, GST_OUTPUT_LEDGER_ID, 111L, AccountNature.LIABILITY);
        register(ledgers, groups, byControlKey, ControlAccount.CASH, CASH_LEDGER_ID, 112L, AccountNature.ASSET);
        register(ledgers, groups, byControlKey, ControlAccount.SUNDRY_DEBTORS, SUNDRY_DEBTORS_LEDGER_ID, 113L, AccountNature.ASSET);

        when(ledgerRepo.findByControlKey(any()))
                .thenAnswer(inv -> Optional.ofNullable(byControlKey.get(inv.<String>getArgument(0))));
        when(groupRepo.findById(anyLong()))
                .thenAnswer(inv -> Optional.ofNullable(groups.get(inv.<Long>getArgument(0))));

        ControlAccountResolver resolver = new ControlAccountResolver(ledgerRepo);
        SettingsService settingsService = new RecordingSettingsService();

        return new LedgerAutoPostingService(orderRepository, purchaseOrderRepository, expenseRepository,
                resolver, groupRepo, settingsService);
    }

    private static void register(Map<Long, LedgerAccount> ledgers, Map<Long, AccountGroup> groups,
                                 Map<String, LedgerAccount> byControlKey, ControlAccount control,
                                 long ledgerId, long groupId, AccountNature nature) {
        AccountGroup group = new AccountGroup("Group " + groupId, nature, null, true);
        setId(group, groupId);
        groups.put(groupId, group);
        LedgerAccount ledger = new LedgerAccount(control.name(), groupId, control.key(), true);
        setId(ledger, ledgerId);
        ledgers.put(ledgerId, ledger);
        byControlKey.put(control.key(), ledger);
    }

    /** A {@link SettingsService} subclass returning a fixed seller state (Java 25: no mocking concretes). */
    private static final class RecordingSettingsService extends SettingsService {
        private final AppSettings settings = AppSettings.defaults();

        RecordingSettingsService() {
            super(null);
            settings.setState(SELLER_STATE);
        }

        @Override
        public AppSettings getSettings() {
            return settings;
        }
    }

    private static void setId(Object entity, Long id) {
        try {
            Field field = entity.getClass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unable to set id on " + entity.getClass(), e);
        }
    }
}
