package com.shifa.oms.reporting;

import com.shifa.oms.auth.CurrentUserService;
import com.shifa.oms.auth.Role;
import com.shifa.oms.auth.SalespersonScopeResolver;
import com.shifa.oms.auth.User;
import com.shifa.oms.auth.UserRepository;
import org.springframework.security.access.AccessDeniedException;
import com.shifa.oms.courier.CourierRecord;
import com.shifa.oms.courier.CourierRecordRepository;
import com.shifa.oms.order.OrderEntity;
import com.shifa.oms.order.OrderLineItem;
import com.shifa.oms.order.OrderRepository;
import com.shifa.oms.product.ProductRepository;
import com.shifa.oms.reconciliation.ReceivableEntity;
import com.shifa.oms.reconciliation.ReceivableRepository;
import com.shifa.oms.reconciliation.domain.ReceivableType;
import com.shifa.oms.reporting.domain.DateRange;
import com.shifa.oms.reporting.domain.OrderReportRecord;
import com.shifa.oms.reporting.domain.PercentChange;
import com.shifa.oms.reporting.domain.ReportAggregator;
import com.shifa.oms.reporting.domain.ReportTableBuilder;
import com.shifa.oms.reporting.domain.ReportType;
import com.shifa.oms.reporting.domain.TabularData;
import com.shifa.oms.reporting.domain.VyaparTableBuilder;
import com.shifa.oms.reporting.dto.ReportResponse;
import com.shifa.oms.reporting.dto.ReportSummary;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The reporting/export service (Req 20.1&ndash;20.4, 23.1, 23.2).
 *
 * <p>It loads the orders visible to the current user &mdash; salesperson-scoped
 * to their own orders, all orders for admin/accountant (Req 5.4, 5.5, 20.3)
 * &mdash; and joins each with its receivables (COD settlement and loss-claim
 * status) and courier record (AWB) to build the pure
 * {@link OrderReportRecord}s. All windowing and aggregation is then delegated to
 * the pure {@link ReportAggregator} / {@link ReportTableBuilder} (Req 20.2), so
 * the exported files reproduce exactly what the report shows (Property 24).
 */
@Service
public class ReportService {

    private final OrderRepository orderRepository;
    private final ReceivableRepository receivableRepository;
    private final CourierRecordRepository courierRecordRepository;
    private final CurrentUserService currentUserService;
    private final SalespersonScopeResolver scopeResolver;
    private final UserRepository userRepository;
    private final ModuleReportService moduleReportService;
    private final ProductRepository productRepository;

    private final ReportAggregator aggregator = new ReportAggregator();
    private final ReportTableBuilder tableBuilder = new ReportTableBuilder(aggregator);
    private final VyaparTableBuilder vyaparBuilder = new VyaparTableBuilder();

    public ReportService(OrderRepository orderRepository,
                         ReceivableRepository receivableRepository,
                         CourierRecordRepository courierRecordRepository,
                         CurrentUserService currentUserService,
                         SalespersonScopeResolver scopeResolver,
                         UserRepository userRepository,
                         ModuleReportService moduleReportService,
                         ProductRepository productRepository) {
        this.orderRepository = orderRepository;
        this.receivableRepository = receivableRepository;
        this.courierRecordRepository = courierRecordRepository;
        this.currentUserService = currentUserService;
        this.scopeResolver = scopeResolver;
        this.userRepository = userRepository;
        this.moduleReportService = moduleReportService;
        this.productRepository = productRepository;
    }

    /** Generates a report of the given type over the window, for the current user. */
    @Transactional(readOnly = true)
    public ReportResponse generate(ReportType type, LocalDate from, LocalDate to) {
        // Per-module operational reports (expenses/procurement/returns/inventory) are
        // business-wide and restricted to ADMIN / ACCOUNTANT (never salesperson-scoped).
        if (type.isModuleReport()) {
            requireAdminOrAccountant();
            return moduleReportService.generate(type, from, to);
        }
        // Money/receivables reports stay ADMIN/ACCOUNTANT-only; a salesperson gets
        // only the sales + orders reports (scoped to their own orders below).
        if (type.isMoneyReport()) {
            requireAdminOrAccountant();
        }
        DateRange window = new DateRange(from, to);
        List<OrderReportRecord> records = loadRecords(window);
        Map<Long, String> salespersonNames = salespersonNames(records);
        TabularData table = tableBuilder.build(type, records, window);
        // Show salesperson names (not raw ids) in the grouped-by-salesperson report.
        table = remapSalespersonColumn(type, table, salespersonNames);
        ReportSummary summary = summarize(records, window, salespersonNames);
        return new ReportResponse(type.name(), from, to, table.headers(), table.rows(), summary);
    }

    /** The displayed table for a report type over the window (backs the exports). */
    @Transactional(readOnly = true)
    public TabularData reportTable(ReportType type, LocalDate from, LocalDate to) {
        DateRange window = new DateRange(from, to);
        return tableBuilder.build(type, loadRecords(window), window);
    }

    /** The Vyapar billing table over the window (Req 23.1). */
    @Transactional(readOnly = true)
    public TabularData vyaparTable(LocalDate from, LocalDate to) {
        DateRange window = new DateRange(from, to);
        return vyaparBuilder.build(loadRecords(window), window);
    }

    // --- Assembly -----------------------------------------------------------

    private ReportSummary summarize(List<OrderReportRecord> records, DateRange window,
                                    Map<Long, String> salespersonNames) {
        PercentChange change = aggregator.salesPercentChange(records, window);
        Long topSalespersonId = aggregator.topSalesperson(records, window).orElse(null);
        String topSalespersonName = topSalespersonId == null
                ? null : salespersonNames.get(topSalespersonId);
        MoneyTotals money = moneyTotals(records, window);
        return new ReportSummary(
                aggregator.totalSales(records, window),
                aggregator.orderCount(records, window),
                change.applicable(),
                change.value(),
                topSalespersonId,
                topSalespersonName,
                aggregator.topProduct(records, window).orElse(null),
                aggregator.topState(records, window).orElse(null),
                money.received, money.outstanding, money.codPending);
    }

    /** Restricts per-module reports to ADMIN / ACCOUNTANT (403 otherwise). */
    private void requireAdminOrAccountant() {
        Role role = currentUserService.currentUser().map(p -> p.role()).orElse(null);
        if (role != Role.ADMIN && role != Role.ACCOUNTANT && role != Role.CA) {
            throw new AccessDeniedException("This report is restricted to admin, accountant and CA.");
        }
    }

    /** Money aggregates for the Finance summary tiles, mirroring the money-report tables. */
    private record MoneyTotals(BigDecimal received, BigDecimal outstanding, BigDecimal codPending) {
    }

    private MoneyTotals moneyTotals(List<OrderReportRecord> records, DateRange window) {
        BigDecimal received = BigDecimal.ZERO;
        BigDecimal outstanding = BigDecimal.ZERO;
        BigDecimal codPending = BigDecimal.ZERO;
        for (OrderReportRecord o : records) {
            if (o.orderDate() == null || !window.contains(o.orderDate())) {
                continue;
            }
            boolean writtenOff = o.orderStatus() == com.shifa.oms.statemachine.OrderStatus.CANCELLED
                    || o.orderStatus() == com.shifa.oms.statemachine.OrderStatus.REJECTED
                    || o.orderStatus() == com.shifa.oms.statemachine.OrderStatus.PAYMENT_REJECTED;
            if (!writtenOff) {
                received = received.add(o.amountReceived());
                BigDecimal balance = o.customerOutstanding() != null
                        ? o.customerOutstanding()
                        : o.totalAmount().subtract(o.amountReceived());
                if (balance.signum() > 0) {
                    outstanding = outstanding.add(balance);
                }
            }
            if ("Pending".equalsIgnoreCase(o.codSettlementStatus())) {
                codPending = codPending.add(o.codAmount());
            }
        }
        return new MoneyTotals(received, outstanding, codPending);
    }

    /**
     * Resolves every salesperson id present in the records to a display name
     * (full name when set, else username) in a single query, so reports show
     * names instead of raw ids.
     */
    private Map<Long, String> salespersonNames(List<OrderReportRecord> records) {
        Set<Long> ids = new HashSet<>();
        for (OrderReportRecord r : records) {
            if (r.salespersonId() != null) {
                ids.add(r.salespersonId());
            }
        }
        Map<Long, String> names = new LinkedHashMap<>();
        if (ids.isEmpty()) {
            return names;
        }
        for (User u : userRepository.findAllById(ids)) {
            String name = (u.getFullName() != null && !u.getFullName().isBlank())
                    ? u.getFullName() : u.getUsername();
            names.put(u.getId(), name);
        }
        return names;
    }

    /**
     * For the reports whose first column is a salesperson id (orders-by-salesperson
     * and the per-salesperson summary), replaces the raw id with the resolved
     * salesperson name (leaving {@code UNSPECIFIED} and any unresolved id as-is).
     * Other report types are returned unchanged.
     */
    private TabularData remapSalespersonColumn(ReportType type, TabularData table,
                                               Map<Long, String> names) {
        int column = ReportTableBuilder.salespersonColumn(type);
        if (column < 0 || table.rows().isEmpty()) {
            return table;
        }
        List<List<String>> rows = new ArrayList<>(table.rows().size());
        for (List<String> row : table.rows()) {
            List<String> copy = new ArrayList<>(row);
            String key = copy.get(column);
            try {
                String name = names.get(Long.parseLong(key));
                if (name != null) {
                    copy.set(column, name);
                }
            } catch (NumberFormatException ignored) {
                // Non-numeric key (e.g. UNSPECIFIED) — leave unchanged.
            }
            rows.add(copy);
        }
        return new TabularData(table.headers(), rows);
    }

    /** Loads only the requested window; null bounds retain explicit all-time semantics. */
    private List<OrderReportRecord> loadRecords(DateRange window) {
        Long createdBy = currentUserService.currentUser()
                .flatMap(scopeResolver::creatorConstraint)
                .orElse(null);
        java.time.LocalDateTime fromTs = window.from() == null ? null : window.from().atStartOfDay();
        java.time.LocalDateTime toTs = window.to() == null ? null : window.to().plusDays(1).atStartOfDay();
        List<OrderEntity> orders = (fromTs == null && toTs == null)
                ? orderRepository.findAllScoped(createdBy)
                : orderRepository.findAllScopedBetween(createdBy, fromTs, toTs);
        Map<Long, String> categories = productCategories();
        OrderExtras extras = loadExtras(orders);
        List<OrderReportRecord> records = new ArrayList<>(orders.size());
        for (OrderEntity o : orders) {
            records.add(toRecord(o, categories, extras));
        }
        return records;
    }

    /** Max ids per {@code IN (...)} batch; keeps statements small and the count flat up to this many orders. */
    private static final int BATCH_SIZE = 1000;

    /**
     * The per-order data a report row needs beyond the order itself (AWB, COD
     * settlement status, loss-claim status), keyed by order id.
     */
    private record OrderExtras(Map<Long, String> awbByOrder,
                               Map<Long, String> codStatusByOrder,
                               Map<Long, String> claimStatusByOrder) {
        String awb(Long orderId) {
            return awbByOrder.get(orderId);
        }

        String codStatus(Long orderId) {
            return codStatusByOrder.getOrDefault(orderId, NO_RECEIVABLE);
        }

        String claimStatus(Long orderId) {
            return claimStatusByOrder.getOrDefault(orderId, NO_RECEIVABLE);
        }
    }

    /** Status shown when an order has no receivable of the relevant kind. */
    private static final String NO_RECEIVABLE = "N/A";

    /**
     * Batch-loads everything {@link #toRecord} would otherwise fetch one order
     * at a time. Reports used to run four queries per order (lazy line items,
     * courier record, COD receivable, claim receivable) — ~9,000 round-trips for
     * a month of 2,300 orders, i.e. 20+ seconds. This runs a constant handful of
     * queries per {@value #BATCH_SIZE} orders instead.
     *
     * <p>The line-item fetch is run for its side effect: the orders are already
     * managed in this session, and a fetch join initialises each one's lazy
     * {@code lineItems} collection, so {@code getLineItems()} later hits memory.
     */
    private OrderExtras loadExtras(List<OrderEntity> orders) {
        Map<Long, String> awbs = new java.util.HashMap<>();
        Map<Long, List<ReceivableEntity>> cod = new java.util.HashMap<>();
        Map<Long, List<ReceivableEntity>> claims = new java.util.HashMap<>();

        List<Long> ids = new ArrayList<>(orders.size());
        for (OrderEntity o : orders) {
            ids.add(o.getId());
        }
        for (int start = 0; start < ids.size(); start += BATCH_SIZE) {
            List<Long> chunk = ids.subList(start, Math.min(start + BATCH_SIZE, ids.size()));
            orderRepository.findByIdInWithLineItems(chunk);
            for (CourierRecord record : courierRecordRepository.findByOrderIdIn(chunk)) {
                // One record per order; if data ever holds duplicates, keep the first AWB that is set.
                awbs.merge(record.getOrderId(), nullToEmpty(record.getAwb()),
                        (kept, other) -> kept.isEmpty() ? other : kept);
            }
            groupByOrder(receivableRepository.findByOrderIdInAndType(chunk, ReceivableType.COD_RECEIVABLE), cod);
            groupByOrder(receivableRepository.findByOrderIdInAndType(chunk, ReceivableType.CLAIM_RECEIVABLE), claims);
        }

        // An order with a courier record but no AWB yet reports no AWB, exactly as before.
        awbs.replaceAll((orderId, awb) -> awb.isEmpty() ? null : awb);

        Map<Long, String> codStatuses = new java.util.HashMap<>();
        cod.forEach((orderId, rows) -> codStatuses.put(orderId, codSettlementStatus(rows)));
        Map<Long, String> claimStatuses = new java.util.HashMap<>();
        claims.forEach((orderId, rows) -> claimStatuses.put(orderId, claimStatus(rows)));
        return new OrderExtras(awbs, codStatuses, claimStatuses);
    }

    private static void groupByOrder(List<ReceivableEntity> rows, Map<Long, List<ReceivableEntity>> into) {
        for (ReceivableEntity row : rows) {
            into.computeIfAbsent(row.getOrderId(), k -> new ArrayList<>()).add(row);
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /** Product id → category name for every categorised product (one small query per report). */
    private Map<Long, String> productCategories() {
        Map<Long, String> categories = new java.util.HashMap<>();
        for (ProductRepository.ProductCategoryRow row : productRepository.findProductCategoryRows()) {
            if (row.getProductId() != null && row.getCategoryName() != null
                    && !row.getCategoryName().isBlank()) {
                categories.put(row.getProductId(), row.getCategoryName());
            }
        }
        return categories;
    }

    private OrderReportRecord toRecord(OrderEntity o, Map<Long, String> categories, OrderExtras extras) {
        List<OrderReportRecord.ProductLine> products = new ArrayList<>();
        for (OrderLineItem line : o.getLineItems()) {
            products.add(new OrderReportRecord.ProductLine(
                    line.getProductName(), line.getQuantity(), line.getRate(), line.getLineTotal(),
                    line.getProductId() == null ? null : categories.get(line.getProductId())));
        }
        LocalDate orderDate = o.getCreatedAt() != null ? o.getCreatedAt().toLocalDate() : null;
        String awb = extras.awb(o.getId());
        String codStatus = extras.codStatus(o.getId());
        String claimStatus = extras.claimStatus(o.getId());
        return new OrderReportRecord(
                o.getId(),
                o.getOrderCode(),
                orderDate,
                o.getCreatedBy(),
                o.getCustomerName(),
                o.getCustomerMobile(),
                o.getState(),
                products,
                o.getTotalAmount(),
                o.getAmountReceived(),
                o.getCodAmount(),
                o.getPaymentStatus(),
                o.getOrderStatus(),
                codStatus,
                claimStatus,
                awb,
                o.getLeadSource(),
                o.getCustomerOutstanding());
    }

    /**
     * COD settlement status from the receivables ledger (Req 20.3): a settled COD
     * receivable reads "Received-from-courier", an unsettled one "Pending", and
     * no COD receivable "N/A".
     */
    private static String codSettlementStatus(List<ReceivableEntity> cod) {
        if (cod.isEmpty()) {
            return NO_RECEIVABLE;
        }
        boolean settled = cod.stream().anyMatch(ReceivableEntity::isSettled);
        return settled ? "Received-from-courier" : "Pending";
    }

    /**
     * Loss claim status from the receivables ledger (Req 20.3): a settled claim
     * reads "Received", an unsettled one "Pending", and no claim "N/A".
     */
    private static String claimStatus(List<ReceivableEntity> claims) {
        if (claims.isEmpty()) {
            return NO_RECEIVABLE;
        }
        boolean settled = claims.stream().anyMatch(ReceivableEntity::isSettled);
        return settled ? "Received" : "Pending";
    }
}
