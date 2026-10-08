package com.shifa.oms.ledger.autopost;

import com.shifa.oms.common.ValidationException;
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
import com.shifa.oms.order.OrderSource;
import com.shifa.oms.order.OrderStatusHistory;
import com.shifa.oms.order.OrderRepository;
import com.shifa.oms.order.domain.PaymentStatus;
import com.shifa.oms.procurement.PurchaseOrderRepository;
import com.shifa.oms.settings.AppSettings;
import com.shifa.oms.settings.AppSettingsRepository;
import com.shifa.oms.settings.SettingsService;
import com.shifa.oms.statemachine.OrderStatus;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for the {@link SourceType#ORDER_DELIVERY} posting — the COD cash collected when an order is
 * delivered, booked on the DELIVERY date.
 *
 * <p>Why this source exists: the sales voucher posted at admin approval debits Sundry Debtors for a COD
 * order (nothing is paid at entry), and before this source nothing ever credited that balance back when
 * the cash was actually collected on delivery. Sundry Debtors therefore grew with every delivered COD
 * order while Cash stayed understated. The existing {@link SourceType#PAYMENT} source does not cover it:
 * that fires on prepaid payment verification, which never happens for a pure-COD order.
 *
 * <p>The defining behaviour asserted here is the voucher DATE: the collection must land on the day the
 * order reached {@code DELIVERED}, not the order-entry date that every other order-derived source uses.
 *
 * <p>Per the project's Java 25 gotcha (Mockito cannot mock concrete classes) only Spring Data repository
 * <em>interfaces</em> are mocked; a REAL {@link ControlAccountResolver} is built over the mocked
 * {@link LedgerAccountRepository} and the concrete {@link SettingsService} is a recording subclass. The
 * pure {@link DoubleEntry#validate(DraftVoucher)} is the balanced/structural oracle.
 */
class DeliveryReceiptPostingTest {

    private static final Long ORDER_ID = 4242L;
    private static final long LEDGER_ID_BASE = 1000L;
    private static final long GROUP_ID_BASE = 2000L;

    /** The order was entered on this date — deliberately NOT the delivery date. */
    private static final LocalDateTime ENTERED_AT = LocalDateTime.of(2026, 3, 2, 10, 0);
    private static final LocalDateTime DELIVERED_AT = LocalDateTime.of(2026, 3, 19, 17, 45);

    @Test
    void codCollectedOnDeliveryPostsAReceiptDatedTheDeliveryDate() {
        OrderEntity order = codOrderDelivered(new BigDecimal("1500.00"), DELIVERED_AT);
        LedgerAutoPostingService service = serviceFor(order);

        DraftVoucher draft = service.buildDraft(SourceType.ORDER_DELIVERY, ORDER_ID);

        // The whole point of this source: the voucher is dated the DELIVERY date, not order entry.
        assertThat(draft.date())
                .as("the COD collection is booked on the day it was collected (delivery), not order entry")
                .isEqualTo(LocalDate.of(2026, 3, 19));
        assertThat(draft.date())
                .as("the delivery date must differ from the order-entry date in this fixture")
                .isNotEqualTo(ENTERED_AT.toLocalDate());

        assertThat(draft.type()).isEqualTo(VoucherType.RECEIPT);

        // Dr Cash / Cr Sundry Debtors for the COD amount — this is what clears the debtor balance
        // the sales voucher created at approval.
        assertThat(controlsOnSide(draft, true)).containsExactly(ControlAccount.CASH);
        assertThat(controlsOnSide(draft, false)).containsExactly(ControlAccount.SUNDRY_DEBTORS);
        assertThat(amountFor(draft, ControlAccount.CASH, true))
                .usingComparator(BigDecimal::compareTo)
                .isEqualTo(new BigDecimal("1500.00"));
        assertThat(amountFor(draft, ControlAccount.SUNDRY_DEBTORS, false))
                .usingComparator(BigDecimal::compareTo)
                .isEqualTo(new BigDecimal("1500.00"));

        DoubleEntry.Result result = DoubleEntry.validate(draft);
        assertThat(result.ok())
                .as("the delivery receipt must be a valid balanced double-entry (violations: %s)",
                        result.violations())
                .isTrue();
    }

    @Test
    void aRedispatchedOrderUsesTheMostRecentDeliveryDate() {
        // An order can be delivered more than once across a redispatch; the collection belongs to the
        // delivery that actually settled it, i.e. the latest one.
        OrderEntity order = codOrderDelivered(new BigDecimal("800.00"), DELIVERED_AT);
        addHistory(order, OrderStatus.REDISPATCH, LocalDateTime.of(2026, 3, 20, 9, 0));
        addHistory(order, OrderStatus.DELIVERED, LocalDateTime.of(2026, 3, 25, 12, 30));
        LedgerAutoPostingService service = serviceFor(order);

        DraftVoucher draft = service.buildDraft(SourceType.ORDER_DELIVERY, ORDER_ID);

        assertThat(draft.date())
                .as("the latest delivery is the one being settled")
                .isEqualTo(LocalDate.of(2026, 3, 25));
    }

    @Test
    void settlementDateIsUsedWhenNoDeliveredRowCarriesATimestamp() {
        // Defensive fallback: a history row with no timestamp must not make the voucher undatable.
        OrderEntity order = codOrder(new BigDecimal("300.00"));
        addHistory(order, OrderStatus.DELIVERED, null);
        addHistory(order, OrderStatus.COD_COLLECTED, LocalDateTime.of(2026, 4, 5, 8, 0));
        LedgerAutoPostingService service = serviceFor(order);

        DraftVoucher draft = service.buildDraft(SourceType.ORDER_DELIVERY, ORDER_ID);

        assertThat(draft.date()).isEqualTo(LocalDate.of(2026, 4, 5));
    }

    @Test
    void aPrepaidOrderHasNoDeliveryReceiptToPost() {
        // Nothing was collected on delivery, so there is no receipt. The publishers only enqueue this
        // source for orders carrying a COD amount, so this is the defensive guard.
        OrderEntity order = codOrder(BigDecimal.ZERO.setScale(2));
        addHistory(order, OrderStatus.DELIVERED, DELIVERED_AT);
        LedgerAutoPostingService service = serviceFor(order);

        assertThatThrownBy(() -> service.buildDraft(SourceType.ORDER_DELIVERY, ORDER_ID))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("collected no COD on delivery");
    }

    // --- Fixture helpers -------------------------------------------------------------------------

    /** A COD order with the given COD amount, entered on {@link #ENTERED_AT}. */
    private static OrderEntity codOrder(BigDecimal cod) {
        OrderEntity order = new OrderEntity("SHR-COD-1", OrderSource.SALESPERSON, 1L,
                "Customer", "9999999999", "Address line", "City", "Madhya Pradesh", "452001");
        order.applyAmounts(cod, BigDecimal.ZERO.setScale(2), cod, cod, PaymentStatus.COD);
        writeField(order, "createdAt", ENTERED_AT);
        setId(order, ORDER_ID);
        return order;
    }

    /** A COD order already delivered at the given time. */
    private static OrderEntity codOrderDelivered(BigDecimal cod, LocalDateTime deliveredAt) {
        OrderEntity order = codOrder(cod);
        addHistory(order, OrderStatus.DELIVERED, deliveredAt);
        return order;
    }

    /** Appends a status-history row with an explicit {@code changed_at} (DB-filled in production). */
    private static void addHistory(OrderEntity order, OrderStatus to, LocalDateTime changedAt) {
        OrderStatusHistory history = new OrderStatusHistory(null, to, "COURIER_API", "COURIER");
        writeField(history, "changedAt", changedAt);
        order.addStatusHistory(history);
    }

    /** The control accounts referenced on the requested side (true = debit, false = credit). */
    private static List<ControlAccount> controlsOnSide(DraftVoucher draft, boolean debitSide) {
        return draft.lines().stream()
                .filter(line -> (line.debitAmount().signum() > 0) == debitSide)
                .map(line -> controlForLedgerId(line.ledgerId()))
                .toList();
    }

    /** The amount posted to a control account on the requested side. */
    private static BigDecimal amountFor(DraftVoucher draft, ControlAccount control, boolean debitSide) {
        for (PostingLine line : draft.lines()) {
            boolean isDebit = line.debitAmount().signum() > 0;
            if (isDebit == debitSide && controlForLedgerId(line.ledgerId()) == control) {
                return isDebit ? line.debitAmount() : line.creditAmount();
            }
        }
        throw new AssertionError("No " + (debitSide ? "debit" : "credit") + " line for " + control);
    }

    private static ControlAccount controlForLedgerId(Long ledgerId) {
        return ControlAccount.values()[(int) (ledgerId - LEDGER_ID_BASE)];
    }

    /** A real {@link LedgerAutoPostingService} over mocked repositories returning {@code order}. */
    private static LedgerAutoPostingService serviceFor(OrderEntity order) {
        OrderRepository orderRepo = mock(OrderRepository.class);
        LedgerAccountRepository ledgerRepo = mock(LedgerAccountRepository.class);
        AccountGroupRepository groupRepo = mock(AccountGroupRepository.class);

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
        when(orderRepo.findById(ORDER_ID)).thenReturn(Optional.of(order));

        return new LedgerAutoPostingService(
                orderRepo,
                mock(PurchaseOrderRepository.class),
                mock(ExpenseRepository.class),
                new ControlAccountResolver(ledgerRepo),
                groupRepo,
                new FixedSettingsService());
    }

    private static AccountNature natureFor(ControlAccount control) {
        return switch (control) {
            case SUNDRY_DEBTORS, GST_INPUT, CASH, BANK -> AccountNature.ASSET;
            case SUNDRY_CREDITORS, GST_OUTPUT -> AccountNature.LIABILITY;
            case SALES -> AccountNature.INCOME;
            case PURCHASES, DEFAULT_EXPENSE -> AccountNature.EXPENSE;
        };
    }

    /** A recording {@link SettingsService} subclass with a fixed seller state (Java 25 gotcha). */
    private static final class FixedSettingsService extends SettingsService {
        private final AppSettings settings;

        FixedSettingsService() {
            super(mock(AppSettingsRepository.class));
            this.settings = AppSettings.defaults();
            this.settings.setState("Madhya Pradesh");
        }

        @Override
        public AppSettings getSettings() {
            return settings;
        }
    }

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
