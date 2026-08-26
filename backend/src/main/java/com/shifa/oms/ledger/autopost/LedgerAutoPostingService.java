package com.shifa.oms.ledger.autopost;

import com.shifa.oms.common.ResourceNotFoundException;
import com.shifa.oms.common.ValidationException;
import com.shifa.oms.finance.Expense;
import com.shifa.oms.finance.ExpenseRepository;
import com.shifa.oms.gst.domain.GstEngine;
import com.shifa.oms.gst.domain.GstEngine.GstLine;
import com.shifa.oms.gst.domain.GstEngine.TaxSplit;
import com.shifa.oms.gst.domain.SupplyType;
import com.shifa.oms.ledger.AccountGroup;
import com.shifa.oms.ledger.AccountGroupRepository;
import com.shifa.oms.ledger.LedgerAccount;
import com.shifa.oms.ledger.domain.AccountNature;
import com.shifa.oms.ledger.domain.DraftVoucher;
import com.shifa.oms.ledger.domain.PostingLine;
import com.shifa.oms.ledger.domain.VoucherType;
import com.shifa.oms.order.OrderEntity;
import com.shifa.oms.order.OrderLineItem;
import com.shifa.oms.order.OrderRepository;
import com.shifa.oms.order.domain.PaymentStatus;
import com.shifa.oms.procurement.PurchaseOrder;
import com.shifa.oms.procurement.PurchaseOrderRepository;
import com.shifa.oms.settings.SettingsService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Derives a balanced double-entry {@link DraftVoucher} from an existing Shifa OMS business document,
 * one builder per {@link SourceType} (General Ledger auto-posting, Reqs 8, 9, 10, 11).
 *
 * <p>This service performs the <em>derivation</em> half of decoupled auto-posting: given a source
 * type and id, it loads the source aggregate <strong>read-only</strong>, resolves the well-known
 * {@link ControlAccount control ledgers} via {@link ControlAccountResolver} (each line's
 * {@link AccountNature} is derived from the resolved ledger's group, mirroring
 * {@code VoucherService.natureOf}), and assembles a {@link DraftVoucher} whose debits equal its
 * credits by construction. It deliberately <strong>does not post</strong> — the
 * {@code LedgerPostingDrainer} hands the returned draft (plus the source reference) to
 * {@code VoucherService.post}, which enforces balancing + idempotency out-of-band so a posting error
 * can never touch or roll back the source business event (Req 17.4).
 *
 * <p>Per source type:
 * <ul>
 *   <li><strong>Sales (Req 8)</strong> — from an {@link OrderEntity}. Each line's net/GST split reuses
 *       the pure {@link GstEngine#splitLine} (taxable = total / (1 + rate/100)) with the same
 *       intra/inter-state classification the CA GST dashboard uses, so the Sales-vs-GST split is a
 *       single source of truth (Req 8.2). Debits Cash (fully paid) or Sundry Debtors (credit sale) for
 *       the order gross, credits Sales for the net and GST Output for the tax (Req 8.1). Source ref =
 *       order id (Req 8.3).</li>
 *   <li><strong>Purchase (Req 9)</strong> — from a {@link PurchaseOrder}. Debits Purchases for the net
 *       (plus GST Input for any captured tax) and credits Sundry Creditors for the bill total
 *       (Req 9.1). Source ref = PO id (Req 9.2).</li>
 *   <li><strong>Expense (Req 10)</strong> — from an {@link Expense}. Debits the mapped expense ledger
 *       for the category, or {@link ControlAccount#DEFAULT_EXPENSE} when unmapped (Req 10.4), and
 *       credits Cash as a Payment voucher. Source ref = expense id (Req 10.2).</li>
 *   <li><strong>Receipt/Payment (Req 11)</strong> — from a payment record. A customer receipt debits
 *       Cash and credits Sundry Debtors as a Receipt voucher (Req 11.1); source ref = payment id
 *       (Req 11.3).</li>
 * </ul>
 *
 * <p>Money is normalised to {@link BigDecimal} scale-2 {@code HALF_UP}, matching the codebase-wide
 * convention. Constructor injection and {@code @Transactional(readOnly = true)} follow the module's
 * read-service style.
 */
@Service
@Transactional(readOnly = true)
public class LedgerAutoPostingService {

    /** Money scale matching the codebase-wide {@code BigDecimal} scale-2 {@code HALF_UP} convention. */
    private static final int MONEY_SCALE = 2;
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);

    private final OrderRepository orderRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final ExpenseRepository expenseRepository;
    private final ControlAccountResolver controlAccountResolver;
    private final AccountGroupRepository accountGroupRepository;
    private final SettingsService settingsService;

    public LedgerAutoPostingService(OrderRepository orderRepository,
                                    PurchaseOrderRepository purchaseOrderRepository,
                                    ExpenseRepository expenseRepository,
                                    ControlAccountResolver controlAccountResolver,
                                    AccountGroupRepository accountGroupRepository,
                                    SettingsService settingsService) {
        this.orderRepository = orderRepository;
        this.purchaseOrderRepository = purchaseOrderRepository;
        this.expenseRepository = expenseRepository;
        this.controlAccountResolver = controlAccountResolver;
        this.accountGroupRepository = accountGroupRepository;
        this.settingsService = settingsService;
    }

    /**
     * Build a balanced {@link DraftVoucher} from the given source business document (Reqs 8.1, 8.2,
     * 9.1, 10.1, 10.4, 11.1, 11.2).
     *
     * @param sourceType the kind of source document to derive the voucher from
     * @param sourceId   the source document's identifier
     * @return a balanced draft awaiting {@code VoucherService.post}
     * @throws ValidationException       when {@code sourceType}/{@code sourceId} is null
     * @throws ResourceNotFoundException when the source document (or a required control ledger) does
     *                                   not exist
     */
    public DraftVoucher buildDraft(SourceType sourceType, Long sourceId) {
        if (sourceType == null) {
            throw new ValidationException("A source type is required to build a ledger voucher.");
        }
        if (sourceId == null) {
            throw new ValidationException("A source id is required to build a ledger voucher.");
        }
        return switch (sourceType) {
            case ORDER -> buildSalesDraft(sourceId);
            case PURCHASE_ORDER -> buildPurchaseDraft(sourceId);
            case EXPENSE -> buildExpenseDraft(sourceId);
            case PAYMENT -> buildReceiptDraft(sourceId);
        };
    }

    // --- Sales (Req 8) -------------------------------------------------------

    /**
     * Sales voucher from an order: debit Cash (fully paid) or Sundry Debtors (credit sale) for the
     * order gross, credit Sales for the GST-net value and GST Output for the tax (Reqs 8.1, 8.2, 8.3).
     * The net/GST split reuses {@link GstEngine#splitLine} with the CA-dashboard state classification.
     */
    private DraftVoucher buildSalesDraft(Long orderId) {
        OrderEntity order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order " + orderId + " was not found."));

        SupplyType supplyType = GstEngine.classify(order.getState(), settingsService.getSettings().getState());
        BigDecimal net = ZERO;
        BigDecimal gst = ZERO;
        for (OrderLineItem li : order.getLineItems()) {
            TaxSplit split = GstEngine.splitLine(
                    new GstLine(li.getHsnCode(), li.getProductName(), li.getGstRate(),
                            li.getQuantity(), li.getLineTotal()),
                    supplyType);
            net = net.add(split.taxable());
            gst = gst.add(split.totalTax());
        }
        net = scale(net);
        gst = scale(gst);
        // The gross equals net + gst (== the sum of the GST-inclusive line totals), so the voucher
        // balances by construction: debit gross == credit Sales (net) + credit GST Output (gst).
        BigDecimal gross = scale(net.add(gst));

        ControlAccount debitAccount = isFullyPaid(order) ? ControlAccount.CASH : ControlAccount.SUNDRY_DEBTORS;

        List<PostingLine> lines = new ArrayList<>();
        lines.add(debit(debitAccount, gross));
        lines.add(credit(ControlAccount.SALES, net));
        if (gst.signum() > 0) {
            lines.add(credit(ControlAccount.GST_OUTPUT, gst));
        }

        String narration = "Sales invoice for order " + describe(order.getOrderCode(), orderId);
        return new DraftVoucher(VoucherType.SALES, dateOf(order.getCreatedAt()), narration, lines);
    }

    /** Whether the order is fully settled at entry (nothing left to collect). */
    private static boolean isFullyPaid(OrderEntity order) {
        BigDecimal remaining = order.getRemainingAmount();
        if (remaining != null) {
            return remaining.signum() <= 0;
        }
        return order.getPaymentStatus() == PaymentStatus.FULLY_PAID;
    }

    // --- Purchase (Req 9) ----------------------------------------------------

    /**
     * Purchase voucher from a purchase order: debit Purchases for the net (plus GST Input for any
     * captured tax) and credit Sundry Creditors for the bill total (Reqs 9.1, 9.2). The procurement
     * module does not currently capture a separate GST component, so the whole bill is treated as net
     * (tax = 0) and no GST Input line is added; when procurement starts capturing purchase GST, the
     * tax branch below emits the GST Input debit without any other change.
     */
    private DraftVoucher buildPurchaseDraft(Long purchaseOrderId) {
        PurchaseOrder po = purchaseOrderRepository.findById(purchaseOrderId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Purchase order " + purchaseOrderId + " was not found."));

        BigDecimal total = scale(nz(po.getTotalAmount()));
        BigDecimal tax = ZERO;                 // procurement captures no purchase GST yet (Req 9 note)
        BigDecimal net = scale(total.subtract(tax));

        List<PostingLine> lines = new ArrayList<>();
        lines.add(debit(ControlAccount.PURCHASES, net));
        if (tax.signum() > 0) {
            lines.add(debit(ControlAccount.GST_INPUT, tax));
        }
        lines.add(credit(ControlAccount.SUNDRY_CREDITORS, total));

        String narration = "Purchase bill for PO " + describe(po.getPoNumber(), purchaseOrderId);
        return new DraftVoucher(VoucherType.PURCHASE, dateOf(po.getCreatedAt()), narration, lines);
    }

    // --- Expense (Req 10) ----------------------------------------------------

    /**
     * Expense voucher from a recorded expense: debit the expense ledger for the category (falling back
     * to {@link ControlAccount#DEFAULT_EXPENSE} when the category is unmapped, Req 10.4) and credit
     * Cash as a Payment voucher (Reqs 10.1, 10.2). No per-category ledger mapping exists yet, so every
     * expense debits the default expense ledger.
     */
    private DraftVoucher buildExpenseDraft(Long expenseId) {
        Expense expense = expenseRepository.findById(expenseId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Expense " + expenseId + " was not found."));

        BigDecimal amount = scale(nz(expense.getAmount()));

        List<PostingLine> lines = new ArrayList<>();
        // Req 10.4: no category → ledger mapping is configured, so post to the default expense ledger.
        lines.add(debit(ControlAccount.DEFAULT_EXPENSE, amount));
        lines.add(credit(ControlAccount.CASH, amount));

        String category = expense.getCategory() == null || expense.getCategory().isBlank()
                ? "expense" : expense.getCategory();
        String narration = "Expense (" + category + ") #" + expenseId;
        LocalDate date = expense.getIncurredOn() != null ? expense.getIncurredOn() : dateOf(expense.getCreatedAt());
        return new DraftVoucher(VoucherType.PAYMENT, date, narration, lines);
    }

    // --- Receipt / Payment (Req 11) ------------------------------------------

    /**
     * Receipt voucher from a customer payment received against an order: debit Cash and credit Sundry
     * Debtors for the amount received (Reqs 11.1, 11.3). This models the only payment record Shifa OMS
     * currently persists — the customer receipt captured on an order. A supplier-payment source
     * (Req 11.2: debit Sundry Creditors, credit Cash/Bank) maps in the same way once the procurement
     * module records supplier payments as their own document.
     */
    private DraftVoucher buildReceiptDraft(Long paymentId) {
        OrderEntity order = orderRepository.findById(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Payment (order) " + paymentId + " was not found."));

        BigDecimal received = scale(nz(order.getAmountReceived()));

        List<PostingLine> lines = new ArrayList<>();
        lines.add(debit(ControlAccount.CASH, received));
        lines.add(credit(ControlAccount.SUNDRY_DEBTORS, received));

        String narration = "Customer receipt for order " + describe(order.getOrderCode(), paymentId);
        return new DraftVoucher(VoucherType.RECEIPT, dateOf(order.getCreatedAt()), narration, lines);
    }

    // --- Control-ledger resolution + line helpers ----------------------------

    /** A debit posting line against the ledger mapped to {@code control}, carrying its derived nature. */
    private PostingLine debit(ControlAccount control, BigDecimal amount) {
        LedgerAccount ledger = controlAccountResolver.resolveLedger(control);
        return PostingLine.debit(ledger.getId(), natureOf(ledger), amount);
    }

    /** A credit posting line against the ledger mapped to {@code control}, carrying its derived nature. */
    private PostingLine credit(ControlAccount control, BigDecimal amount) {
        LedgerAccount ledger = controlAccountResolver.resolveLedger(control);
        return PostingLine.credit(ledger.getId(), natureOf(ledger), amount);
    }

    /** The {@link AccountNature} of a ledger account, derived from its owning group (Req 2.2). */
    private AccountNature natureOf(LedgerAccount ledger) {
        AccountGroup group = accountGroupRepository.findById(ledger.getAccountGroupId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Account group " + ledger.getAccountGroupId() + " was not found."));
        return group.getNature();
    }

    // --- Small helpers -------------------------------------------------------

    private static BigDecimal scale(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? ZERO : value;
    }

    private static LocalDate dateOf(LocalDateTime timestamp) {
        return timestamp != null ? timestamp.toLocalDate() : LocalDate.now();
    }

    private static String describe(String reference, Long id) {
        return reference == null || reference.isBlank() ? "#" + id : reference;
    }
}
