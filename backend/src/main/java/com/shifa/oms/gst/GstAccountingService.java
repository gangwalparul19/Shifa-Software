package com.shifa.oms.gst;

import com.shifa.oms.common.ValidationException;
import com.shifa.oms.finance.Expense;
import com.shifa.oms.finance.ExpenseRepository;
import com.shifa.oms.gst.domain.GstEngine;
import com.shifa.oms.gst.domain.GstEngine.GstComputation;
import com.shifa.oms.gst.domain.GstEngine.GstLine;
import com.shifa.oms.gst.domain.GstEngine.GstOrder;
import com.shifa.oms.gst.domain.GstEngine.TaxSplit;
import com.shifa.oms.gst.domain.SupplyType;
import com.shifa.oms.gst.dto.GstDashboardResponse;
import com.shifa.oms.gst.dto.GstOrderRow;
import com.shifa.oms.gst.dto.GstDashboardResponse.CategoryAmount;
import com.shifa.oms.gst.dto.GstDashboardResponse.MoneyFlows;
import com.shifa.oms.gst.dto.GstReportResponse;
import com.shifa.oms.order.OrderEntity;
import com.shifa.oms.order.OrderLineItem;
import com.shifa.oms.order.OrderRepository;
import com.shifa.oms.procurement.PurchaseOrder;
import com.shifa.oms.procurement.PurchaseOrderRepository;
import com.shifa.oms.returns.OrderReturn;
import com.shifa.oms.returns.OrderReturnRepository;
import com.shifa.oms.returns.ReturnStatus;
import com.shifa.oms.settings.AppSettings;
import com.shifa.oms.settings.SettingsService;
import com.shifa.oms.statemachine.OrderStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * CA accounting / GST application service (CA GST dashboard, Reqs 2-7).
 *
 * <p>Computes the filing-ready outward GST report and the money in/out dashboard
 * for a period from the immutable order line tax snapshots + the seller's GST
 * settings. Read-only; all tax math is delegated to the pure {@link GstEngine}.
 */
@Service
public class GstAccountingService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
    /** Orders excluded from tax and sales figures (Req 3.3). */
    private static final Set<OrderStatus> NON_REVENUE =
            EnumSet.of(OrderStatus.CANCELLED, OrderStatus.REJECTED);
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

    private final OrderRepository orderRepository;
    private final ExpenseRepository expenseRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final OrderReturnRepository orderReturnRepository;
    private final SettingsService settingsService;
    private final Clock clock;

    @Autowired
    public GstAccountingService(OrderRepository orderRepository,
                                ExpenseRepository expenseRepository,
                                PurchaseOrderRepository purchaseOrderRepository,
                                OrderReturnRepository orderReturnRepository,
                                SettingsService settingsService) {
        this(orderRepository, expenseRepository, purchaseOrderRepository, orderReturnRepository,
                settingsService, Clock.system(ZONE));
    }

    public GstAccountingService(OrderRepository orderRepository,
                                ExpenseRepository expenseRepository,
                                PurchaseOrderRepository purchaseOrderRepository,
                                OrderReturnRepository orderReturnRepository,
                                SettingsService settingsService,
                                Clock clock) {
        this.orderRepository = orderRepository;
        this.expenseRepository = expenseRepository;
        this.purchaseOrderRepository = purchaseOrderRepository;
        this.orderReturnRepository = orderReturnRepository;
        this.settingsService = settingsService;
        this.clock = clock;
    }

    /** Resolves a nullable from/to to a concrete window, defaulting to the current month. */
    public Period resolvePeriod(LocalDate from, LocalDate to) {
        LocalDate today = LocalDate.now(clock);
        LocalDate f = from != null ? from : today.withDayOfMonth(1);
        LocalDate t = to != null ? to : today;
        if (f.isAfter(t)) {
            throw new ValidationException("The 'from' date must not be after the 'to' date.");
        }
        return new Period(f, t);
    }

    /** The filing-ready outward GST report for the period (Reqs 3, 4, 5). */
    @Transactional(readOnly = true)
    public GstReportResponse report(LocalDate from, LocalDate to) {
        Period p = resolvePeriod(from, to);
        AppSettings seller = settingsService.getSettings();
        List<OrderEntity> orders = revenueOrders(p);
        GstComputation comp = GstEngine.compute(toGstOrders(orders), seller.getState());
        boolean stateConfigured = seller.getState() != null && !seller.getState().isBlank();
        return new GstReportResponse(
                new GstReportResponse.Seller(
                        seller.getLegalName(), seller.getGstin(), seller.getState(), seller.getStateCode()),
                p.from(), p.to(), stateConfigured,
                comp.rateWise(), comp.hsn(), comp.stateWise(), comp.summary());
    }

    /** The CA dashboard: the GST report plus every money in/out for the period (Req 6). */
    @Transactional(readOnly = true)
    public GstDashboardResponse dashboard(LocalDate from, LocalDate to) {
        Period p = resolvePeriod(from, to);
        GstReportResponse report = report(p.from(), p.to());
        List<OrderEntity> orders = revenueOrders(p);

        BigDecimal amountReceived = ZERO;
        BigDecimal codCollected = ZERO;
        for (OrderEntity o : orders) {
            amountReceived = amountReceived.add(nz(o.getAmountReceived()));
            if (o.getOrderStatus() == OrderStatus.COD_COLLECTED) {
                codCollected = codCollected.add(nz(o.getCodAmount()));
            }
        }

        // Expenses (by business date) + category breakdown.
        BigDecimal expensesTotal = ZERO;
        Map<String, BigDecimal> byCategory = new LinkedHashMap<>();
        for (Expense e : expenseRepository.findByIncurredOnBetween(p.from(), p.to())) {
            BigDecimal amt = nz(e.getAmount());
            expensesTotal = expensesTotal.add(amt);
            byCategory.merge(e.getCategory() == null ? "Other" : e.getCategory(), amt, BigDecimal::add);
        }
        List<CategoryAmount> expensesByCategory = new ArrayList<>();
        byCategory.forEach((k, v) -> expensesByCategory.add(new CategoryAmount(k, v)));

        // Purchases (PO totals created in the window).
        BigDecimal purchases = ZERO;
        for (PurchaseOrder po : purchaseOrderRepository.findAll()) {
            if (within(po.getCreatedAt(), p)) {
                purchases = purchases.add(nz(po.getTotalAmount()));
            }
        }

        // Refunds (returns refunded in the window).
        BigDecimal refunds = ZERO;
        for (OrderReturn r : orderReturnRepository.findAll()) {
            if (r.getStatus() == ReturnStatus.REFUNDED && within(r.getCreatedAt(), p)) {
                refunds = refunds.add(nz(r.getRefundAmount()));
            }
        }

        BigDecimal outstandingCod = nz(orderRepository.sumOutstandingCodActive());
        BigDecimal netCash = amountReceived.add(codCollected)
                .subtract(purchases).subtract(expensesTotal).subtract(refunds);

        MoneyFlows money = new MoneyFlows(
                report.summary().invoiceValue(),
                report.summary().taxableOutward(),
                report.summary().outputTotal(),
                amountReceived, codCollected,
                purchases, expensesTotal, expensesByCategory, refunds,
                outstandingCod, netCash.setScale(2));
        return new GstDashboardResponse(report, money);
    }

    /**
     * The orders contributing to a GST summary figure for the period, for the
     * dashboard drill-down (Req 6). Optionally filtered by place-of-supply
     * {@code state}, a line {@code rate}, and/or a line {@code hsn}; each row
     * carries the order's taxable/tax + total/received/remaining so the CA can
     * track dues. Ordered by remaining (dues first), then newest.
     */
    @Transactional(readOnly = true)
    public List<GstOrderRow> orders(LocalDate from, LocalDate to,
                                    String state, BigDecimal rate, String hsn) {
        Period p = resolvePeriod(from, to);
        String sellerState = settingsService.getSettings().getState();
        String stateFilter = state == null || state.isBlank() ? null : state.trim();
        String hsnFilter = hsn == null || hsn.isBlank() ? null : hsn.trim();
        List<GstOrderRow> rows = new ArrayList<>();
        for (OrderEntity o : revenueOrders(p)) {
            if (stateFilter != null && !matchesState(o.getState(), stateFilter)) {
                continue;
            }
            if (rate != null && !hasLineRate(o, rate)) {
                continue;
            }
            if (hsnFilter != null && !hasLineHsn(o, hsnFilter)) {
                continue;
            }
            SupplyType type = GstEngine.classify(o.getState(), sellerState);
            BigDecimal taxable = ZERO;
            BigDecimal tax = ZERO;
            for (OrderLineItem li : o.getLineItems()) {
                TaxSplit s = GstEngine.splitLine(
                        new GstLine(li.getHsnCode(), li.getProductName(), li.getGstRate(),
                                li.getQuantity(), li.getLineTotal()), type);
                taxable = taxable.add(s.taxable());
                tax = tax.add(s.totalTax());
            }
            BigDecimal remaining = nz(o.getRemainingAmount());
            BigDecimal cod = nz(o.getCodAmount());
            boolean codSettled = o.getOrderStatus() == OrderStatus.COD_COLLECTED
                    || o.getOrderStatus() == OrderStatus.CLOSED;
            // COD still pending from the courier (0 once collected/closed); the rest of
            // the remaining balance is what the customer owes directly.
            BigDecimal codPending = codSettled ? ZERO : cod;
            BigDecimal customerRemaining = remaining.subtract(cod).max(ZERO);
            rows.add(new GstOrderRow(
                    o.getId(), o.getOrderCode(),
                    o.getCreatedAt() != null ? o.getCreatedAt().toLocalDate() : null,
                    o.getCustomerName(), o.getCustomerMobile(), o.getState(), type,
                    taxable.setScale(2), tax.setScale(2), nz(o.getTotalAmount()),
                    nz(o.getAmountReceived()), remaining,
                    customerRemaining.setScale(2), codPending.setScale(2),
                    o.getPaymentStatus() == null ? null : o.getPaymentStatus().name(),
                    o.getOrderStatus() == null ? null : o.getOrderStatus().name()));
        }
        rows.sort((a, b) -> {
            int byRemaining = b.remaining().compareTo(a.remaining());
            if (byRemaining != 0) {
                return byRemaining;
            }
            if (a.orderDate() == null || b.orderDate() == null) {
                return 0;
            }
            return b.orderDate().compareTo(a.orderDate());
        });
        return rows;
    }

    private static boolean matchesState(String orderState, String filter) {
        String s = orderState == null || orderState.isBlank() ? "(unknown)" : orderState.trim();
        return s.equalsIgnoreCase(filter);
    }

    private static boolean hasLineRate(OrderEntity o, BigDecimal rate) {
        for (OrderLineItem li : o.getLineItems()) {
            BigDecimal r = li.getGstRate() == null ? BigDecimal.ZERO : li.getGstRate();
            if (r.compareTo(rate) == 0) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasLineHsn(OrderEntity o, String hsn) {
        for (OrderLineItem li : o.getLineItems()) {
            String h = li.getHsnCode() == null || li.getHsnCode().isBlank() ? "(none)" : li.getHsnCode().trim();
            if (h.equalsIgnoreCase(hsn)) {
                return true;
            }
        }
        return false;
    }

    // --- Helpers ------------------------------------------------------------

    private List<OrderEntity> revenueOrders(Period p) {
        LocalDateTime fromTs = p.from().atStartOfDay();
        LocalDateTime toTs = p.to().plusDays(1).atStartOfDay();
        List<OrderEntity> out = new ArrayList<>();
        for (OrderEntity o : orderRepository.findByCreatedAtBetween(fromTs, toTs)) {
            if (o.getOrderStatus() == null || !NON_REVENUE.contains(o.getOrderStatus())) {
                out.add(o);
            }
        }
        return out;
    }

    private List<GstOrder> toGstOrders(List<OrderEntity> orders) {
        List<GstOrder> result = new ArrayList<>(orders.size());
        for (OrderEntity o : orders) {
            List<GstLine> lines = new ArrayList<>();
            for (OrderLineItem li : o.getLineItems()) {
                lines.add(new GstLine(li.getHsnCode(), li.getProductName(), li.getGstRate(),
                        li.getQuantity(), li.getLineTotal()));
            }
            LocalDate date = o.getCreatedAt() != null ? o.getCreatedAt().toLocalDate() : LocalDate.now(clock);
            result.add(new GstOrder(o.getId(), o.getState(), date, lines));
        }
        return result;
    }

    private static boolean within(LocalDateTime ts, Period p) {
        if (ts == null) {
            return false;
        }
        LocalDate d = ts.toLocalDate();
        return !d.isBefore(p.from()) && !d.isAfter(p.to());
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? ZERO : v;
    }

    /** A resolved reporting window (inclusive dates). */
    public record Period(LocalDate from, LocalDate to) {
    }
}
