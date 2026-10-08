package com.shifa.oms.reporting;

import com.shifa.oms.finance.Expense;
import com.shifa.oms.finance.ExpenseRepository;
import com.shifa.oms.inventory.StockMovement;
import com.shifa.oms.inventory.StockMovementRepository;
import com.shifa.oms.order.OrderEntity;
import com.shifa.oms.order.OrderRepository;
import com.shifa.oms.procurement.PurchaseOrder;
import com.shifa.oms.procurement.PurchaseOrderRepository;
import com.shifa.oms.procurement.Supplier;
import com.shifa.oms.procurement.SupplierRepository;
import com.shifa.oms.product.Product;
import com.shifa.oms.product.ProductRepository;
import com.shifa.oms.reporting.domain.DateRange;
import com.shifa.oms.reporting.domain.ReportType;
import com.shifa.oms.reporting.domain.TabularData;
import com.shifa.oms.reporting.dto.ReportResponse;
import com.shifa.oms.reporting.dto.ReportSummary;
import com.shifa.oms.returns.OrderReturn;
import com.shifa.oms.returns.OrderReturnRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Per-module operational reports (expenses / procurement / returns / inventory)
 * served through the same {@code /api/reports/{type}} + export pipeline as the
 * order/sales reports — {@link ReportService#generate} delegates the module
 * {@link ReportType}s here. Each report produces the canonical
 * {@link TabularData} (so Excel/PDF export reproduces it exactly, Property 24)
 * over an inclusive date window; a {@code null} bound leaves that side open.
 *
 * <p>These are business-wide (not salesperson-scoped) — {@link ReportService}
 * restricts them to ADMIN / ACCOUNTANT before delegating.
 */
@Service
public class ModuleReportService {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE;

    private final ExpenseRepository expenseRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final SupplierRepository supplierRepository;
    private final OrderReturnRepository orderReturnRepository;
    private final OrderRepository orderRepository;
    private final StockMovementRepository stockMovementRepository;
    private final ProductRepository productRepository;

    public ModuleReportService(ExpenseRepository expenseRepository,
                               PurchaseOrderRepository purchaseOrderRepository,
                               SupplierRepository supplierRepository,
                               OrderReturnRepository orderReturnRepository,
                               OrderRepository orderRepository,
                               StockMovementRepository stockMovementRepository,
                               ProductRepository productRepository) {
        this.expenseRepository = expenseRepository;
        this.purchaseOrderRepository = purchaseOrderRepository;
        this.supplierRepository = supplierRepository;
        this.orderReturnRepository = orderReturnRepository;
        this.orderRepository = orderRepository;
        this.stockMovementRepository = stockMovementRepository;
        this.productRepository = productRepository;
    }

    /** Builds a per-module report response for the given (module) type + window. */
    @Transactional(readOnly = true)
    public ReportResponse generate(ReportType type, LocalDate from, LocalDate to) {
        DateRange window = new DateRange(from, to);
        TabularData table = switch (type) {
            case EXPENSES -> expenses(window);
            case PURCHASE_ORDERS -> purchaseOrders(window);
            case RETURNS -> returns(window);
            case STOCK -> stock(window);
            default -> throw new IllegalArgumentException("Not a module report: " + type);
        };
        return new ReportResponse(type.name(), from, to, table.headers(), table.rows(), emptySummary());
    }

    // --- Expenses -----------------------------------------------------------

    private TabularData expenses(DateRange window) {
        List<String> headers = List.of("Date", "Category", "Amount", "Description");
        List<Expense> rows = new ArrayList<>();
        for (Expense e : expenseRepository.findAll()) {
            if (e.getIncurredOn() != null && window.contains(e.getIncurredOn())) {
                rows.add(e);
            }
        }
        rows.sort(Comparator.comparing(Expense::getIncurredOn));
        List<List<String>> cells = new ArrayList<>();
        for (Expense e : rows) {
            cells.add(List.of(
                    e.getIncurredOn().format(DATE),
                    nz(e.getCategory()),
                    money(e.getAmount()),
                    nz(e.getDescription())));
        }
        return new TabularData(headers, cells);
    }

    // --- Purchase orders (procurement) --------------------------------------

    private TabularData purchaseOrders(DateRange window) {
        List<String> headers = List.of(
                "PO Number", "Supplier", "Status", "Total", "Ordered On", "Received On");
        List<PurchaseOrder> rows = new ArrayList<>();
        for (PurchaseOrder po : purchaseOrderRepository.findAll()) {
            if (inWindow(po.getCreatedAt(), window)) {
                rows.add(po);
            }
        }
        rows.sort(Comparator.comparing(PurchaseOrder::getCreatedAt,
                Comparator.nullsLast(Comparator.naturalOrder())));
        Map<Long, String> supplierNames = supplierNames(rows);
        List<List<String>> cells = new ArrayList<>();
        for (PurchaseOrder po : rows) {
            cells.add(List.of(
                    nz(po.getPoNumber()),
                    supplierNames.getOrDefault(po.getSupplierId(), "#" + po.getSupplierId()),
                    po.getStatus() == null ? "" : po.getStatus().name(),
                    money(po.getTotalAmount()),
                    date(po.getCreatedAt()),
                    date(po.getReceivedAt())));
        }
        return new TabularData(headers, cells);
    }

    private Map<Long, String> supplierNames(List<PurchaseOrder> pos) {
        Set<Long> ids = new HashSet<>();
        for (PurchaseOrder po : pos) {
            if (po.getSupplierId() != null) {
                ids.add(po.getSupplierId());
            }
        }
        Map<Long, String> names = new HashMap<>();
        if (!ids.isEmpty()) {
            for (Supplier s : supplierRepository.findAllById(ids)) {
                names.put(s.getId(), s.getName());
            }
        }
        return names;
    }

    // --- Returns ------------------------------------------------------------

    private TabularData returns(DateRange window) {
        List<String> headers = List.of(
                "Order", "Reason", "Status", "Refund", "Restocked", "Created On");
        List<OrderReturn> rows = new ArrayList<>();
        for (OrderReturn r : orderReturnRepository.findAll()) {
            if (inWindow(r.getCreatedAt(), window)) {
                rows.add(r);
            }
        }
        rows.sort(Comparator.comparing(OrderReturn::getCreatedAt,
                Comparator.nullsLast(Comparator.naturalOrder())));
        Map<Long, String> orderCodes = orderCodes(rows);
        List<List<String>> cells = new ArrayList<>();
        for (OrderReturn r : rows) {
            cells.add(List.of(
                    orderCodes.getOrDefault(r.getOrderId(), "#" + r.getOrderId()),
                    nz(r.getReason()),
                    r.getStatus() == null ? "" : r.getStatus().name(),
                    r.getRefundAmount() == null ? "" : money(r.getRefundAmount()),
                    r.isRestocked() ? "Yes" : "No",
                    date(r.getCreatedAt())));
        }
        return new TabularData(headers, cells);
    }

    private Map<Long, String> orderCodes(List<OrderReturn> returns) {
        Set<Long> ids = new HashSet<>();
        for (OrderReturn r : returns) {
            if (r.getOrderId() != null) {
                ids.add(r.getOrderId());
            }
        }
        Map<Long, String> codes = new HashMap<>();
        if (!ids.isEmpty()) {
            for (OrderEntity o : orderRepository.findAllById(ids)) {
                codes.put(o.getId(), o.getOrderCode());
            }
        }
        return codes;
    }

    // --- Inventory (stock ledger) -------------------------------------------

    private TabularData stock(DateRange window) {
        List<String> headers = List.of(
                "Date", "Product", "Type", "Change", "Balance", "Reason");
        List<StockMovement> rows = new ArrayList<>();
        for (StockMovement m : stockMovementRepository.findAll()) {
            if (inWindow(m.getCreatedAt(), window)) {
                rows.add(m);
            }
        }
        rows.sort(Comparator.comparing(StockMovement::getCreatedAt,
                Comparator.nullsLast(Comparator.naturalOrder())));
        Map<Long, String> productNames = productNames(rows);
        List<List<String>> cells = new ArrayList<>();
        for (StockMovement m : rows) {
            String change = (m.getDelta() > 0 ? "+" : "") + m.getDelta();
            cells.add(List.of(
                    date(m.getCreatedAt()),
                    productNames.getOrDefault(m.getProductId(), "#" + m.getProductId()),
                    m.getMovementType() == null ? "" : m.getMovementType().name(),
                    change,
                    Integer.toString(m.getBalanceAfter()),
                    nz(m.getReason())));
        }
        return new TabularData(headers, cells);
    }

    private Map<Long, String> productNames(List<StockMovement> movements) {
        Set<Long> ids = new HashSet<>();
        for (StockMovement m : movements) {
            if (m.getProductId() != null) {
                ids.add(m.getProductId());
            }
        }
        Map<Long, String> names = new HashMap<>();
        if (!ids.isEmpty()) {
            for (Product p : productRepository.findAllById(ids)) {
                names.put(p.getId(), p.getName());
            }
        }
        return names;
    }

    // --- Helpers ------------------------------------------------------------

    /** Whether a {@code LocalDateTime} timestamp's date falls in the window. */
    private static boolean inWindow(LocalDateTime ts, DateRange window) {
        return ts != null && window.contains(ts.toLocalDate());
    }

    private static String date(LocalDateTime ts) {
        return ts == null ? "" : ts.toLocalDate().format(DATE);
    }

    private static String money(BigDecimal v) {
        return (v == null ? BigDecimal.ZERO : v).toPlainString();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /** A zeroed summary — module reports are pure tables (the frontend hides the sales tiles). */
    private static ReportSummary emptySummary() {
        return new ReportSummary(
                BigDecimal.ZERO, 0, false, null, null, null, null, null,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }
}
