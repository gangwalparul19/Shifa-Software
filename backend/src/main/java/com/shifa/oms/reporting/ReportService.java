package com.shifa.oms.reporting;

import com.shifa.oms.auth.CurrentUserService;
import com.shifa.oms.auth.SalespersonScopeResolver;
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

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

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

    private final ReportAggregator aggregator = new ReportAggregator();
    private final ReportTableBuilder tableBuilder = new ReportTableBuilder(aggregator);
    private final VyaparTableBuilder vyaparBuilder = new VyaparTableBuilder();

    public ReportService(OrderRepository orderRepository,
                         ReceivableRepository receivableRepository,
                         CourierRecordRepository courierRecordRepository,
                         CurrentUserService currentUserService,
                         SalespersonScopeResolver scopeResolver) {
        this.orderRepository = orderRepository;
        this.receivableRepository = receivableRepository;
        this.courierRecordRepository = courierRecordRepository;
        this.currentUserService = currentUserService;
        this.scopeResolver = scopeResolver;
    }

    /** Generates a report of the given type over the window, for the current user. */
    @Transactional(readOnly = true)
    public ReportResponse generate(ReportType type, LocalDate from, LocalDate to) {
        DateRange window = new DateRange(from, to);
        List<OrderReportRecord> records = loadRecords();
        TabularData table = tableBuilder.build(type, records, window);
        ReportSummary summary = summarize(records, window);
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

    private ReportSummary summarize(List<OrderReportRecord> records, DateRange window) {
        PercentChange change = aggregator.salesPercentChange(records, window);
        return new ReportSummary(
                aggregator.totalSales(records, window),
                aggregator.orderCount(records, window),
                change.applicable(),
                change.value(),
                aggregator.topSalesperson(records, window).orElse(null),
                aggregator.topProduct(records, window).orElse(null),
                aggregator.topState(records, window).orElse(null));
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
