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

    private final ReportAggregator aggregator = new ReportAggregator();
    private final ReportTableBuilder tableBuilder = new ReportTableBuilder(aggregator);
    private final VyaparTableBuilder vyaparBuilder = new VyaparTableBuilder();

    public ReportService(OrderRepository orderRepository,
                         ReceivableRepository receivableRepository,
                         CourierRecordRepository courierRecordRepository,
                         CurrentUserService currentUserService,
                         SalespersonScopeResolver scopeResolver,
                         UserRepository userRepository,
                         ModuleReportService moduleReportService) {
        this.orderRepository = orderRepository;
        this.receivableRepository = receivableRepository;
        this.courierRecordRepository = courierRecordRepository;
        this.currentUserService = currentUserService;
        this.scopeResolver = scopeResolver;
        this.userRepository = userRepository;
        this.moduleReportService = moduleReportService;
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
        DateRange window = new DateRange(from, to);
        List<OrderReportRecord> records = loadRecords();
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
        return tableBuilder.build(type, loadRecords(), window);
    }

    /** The Vyapar billing table over the window (Req 23.1). */
    @Transactional(readOnly = true)
    public TabularData vyaparTable(LocalDate from, LocalDate to) {
        DateRange window = new DateRange(from, to);
        return vyaparBuilder.build(loadRecords(), window);
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
        if (role != Role.ADMIN && role != Role.ACCOUNTANT) {
            throw new AccessDeniedException("This report is restricted to admin and accountant.");
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
                    || o.orderStatus() == com.shifa.oms.statemachine.OrderStatus.REJECTED;
            if (!writtenOff) {
                received = received.add(o.amountReceived());
                BigDecimal balance = o.totalAmount().subtract(o.amountReceived());
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
     * For the orders-grouped-by-salesperson report, replaces the raw id in the
     * first column with the resolved salesperson name (leaving {@code UNSPECIFIED}
     * and any unresolved id as-is). Other report types are returned unchanged.
     */
    private TabularData remapSalespersonColumn(ReportType type, TabularData table,
                                               Map<Long, String> names) {
        if (type != ReportType.ORDERS_BY_SALESPERSON || table.rows().isEmpty()) {
            return table;
        }
        List<List<String>> rows = new ArrayList<>(table.rows().size());
        for (List<String> row : table.rows()) {
            List<String> copy = new ArrayList<>(row);
            String key = copy.get(0);
            try {
                String name = names.get(Long.parseLong(key));
                if (name != null) {
                    copy.set(0, name);
                }
            } catch (NumberFormatException ignored) {
                // Non-numeric key (e.g. UNSPECIFIED) — leave unchanged.
            }
            rows.add(copy);
        }
        return new TabularData(table.headers(), rows);
    }

    /** Loads the orders visible to the current user as pure report records. */
    private List<OrderReportRecord> loadRecords() {
        Long createdBy = currentUserService.currentUser()
                .flatMap(scopeResolver::creatorConstraint)
                .orElse(null);
        List<OrderEntity> orders = orderRepository.findAllScoped(createdBy);
        List<OrderReportRecord> records = new ArrayList<>(orders.size());
        for (OrderEntity o : orders) {
            records.add(toRecord(o));
        }
        return records;
    }

    private OrderReportRecord toRecord(OrderEntity o) {
        List<OrderReportRecord.ProductLine> products = new ArrayList<>();
        for (OrderLineItem line : o.getLineItems()) {
            products.add(new OrderReportRecord.ProductLine(
                    line.getProductName(), line.getQuantity(), line.getRate(), line.getLineTotal()));
        }
        LocalDate orderDate = o.getCreatedAt() != null ? o.getCreatedAt().toLocalDate() : null;
        String awb = courierRecordRepository.findByOrderId(o.getId())
                .map(CourierRecord::getAwb).orElse(null);
        String codStatus = codSettlementStatus(o.getId());
        String claimStatus = claimStatus(o.getId());
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
                o.getLeadSource());
    }

    /**
     * COD settlement status from the receivables ledger (Req 20.3): a settled COD
     * receivable reads "Received-from-courier", an unsettled one "Pending", and
     * no COD receivable "N/A".
     */
    private String codSettlementStatus(Long orderId) {
        List<ReceivableEntity> cod = receivableRepository
                .findByOrderIdAndType(orderId, ReceivableType.COD_RECEIVABLE);
        if (cod.isEmpty()) {
            return "N/A";
        }
        boolean settled = cod.stream().anyMatch(ReceivableEntity::isSettled);
        return settled ? "Received-from-courier" : "Pending";
    }

    /**
     * Loss claim status from the receivables ledger (Req 20.3): a settled claim
     * reads "Received", an unsettled one "Pending", and no claim "N/A".
     */
    private String claimStatus(Long orderId) {
        List<ReceivableEntity> claims = receivableRepository
                .findByOrderIdAndType(orderId, ReceivableType.CLAIM_RECEIVABLE);
        if (claims.isEmpty()) {
            return "N/A";
        }
        boolean settled = claims.stream().anyMatch(ReceivableEntity::isSettled);
        return settled ? "Received" : "Pending";
    }
}
