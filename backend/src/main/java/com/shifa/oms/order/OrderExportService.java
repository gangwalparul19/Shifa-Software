package com.shifa.oms.order;

import com.shifa.oms.order.domain.PaymentStatus;
import com.shifa.oms.order.dto.OrderSummaryResponse;
import com.shifa.oms.reporting.CsvReportExporter;
import com.shifa.oms.reporting.ExcelReportExporter;
import com.shifa.oms.reporting.domain.TabularData;
import com.shifa.oms.statemachine.OrderStatus;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Builds a CSV/Excel export of the Orders list (list-export enhancement),
 * reusing the SAME filtered + scoped query as the on-screen table
 * ({@link AdminOrderService#listOrders}) and the SAME exporters as the reports
 * module ({@link CsvReportExporter}/{@link ExcelReportExporter} over a
 * {@link TabularData}). So an export is exactly what the user is looking at —
 * salesperson/team-lead scoping is applied identically (the caller passes the
 * server-resolved {@code creatorIds}), never widened.
 */
@Service
public class OrderExportService {

    /** Hard cap on exported rows so an export can't load an unbounded result set. */
    static final int MAX_ROWS = 5000;

    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private static final List<String> HEADERS = List.of(
            "Order Code", "Customer", "Mobile", "Status", "Payment", "Source",
            "Salesperson", "Total", "COD", "Created");

    private final AdminOrderService adminOrderService;
    private final CsvReportExporter csvExporter;
    private final ExcelReportExporter excelExporter;

    public OrderExportService(AdminOrderService adminOrderService,
                              CsvReportExporter csvExporter,
                              ExcelReportExporter excelExporter) {
        this.adminOrderService = adminOrderService;
        this.csvExporter = csvExporter;
        this.excelExporter = excelExporter;
    }

    /** Export result: the bytes plus the content type and download filename. */
    public record ExportResult(byte[] content, String contentType, String filename) {
    }

    /**
     * Builds the export for the given filters/scope.
     *
     * @param format "xlsx" (default) or "csv"
     */
    public ExportResult export(String q, OrderStatus status, OrderStatusGroup statusGroup,
                               PaymentStatus paymentStatus, LocalDate from, LocalDate to,
                               Collection<Long> creatorIds, OrderSource source, String format) {
        TabularData table = buildTable(q, status, statusGroup, paymentStatus, from, to, creatorIds, source);
        boolean csv = "csv".equalsIgnoreCase(format);
        if (csv) {
            return new ExportResult(csvExporter.export(table), "text/csv", "orders.csv");
        }
        return new ExportResult(
                excelExporter.export("Orders", table),
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "orders.xlsx");
    }

    private TabularData buildTable(String q, OrderStatus status, OrderStatusGroup statusGroup,
                                   PaymentStatus paymentStatus, LocalDate from, LocalDate to,
                                   Collection<Long> creatorIds, OrderSource source) {
        // Newest-first, capped at MAX_ROWS — the same default sort as the table.
        Pageable pageable = PageRequest.of(0, MAX_ROWS, Sort.by(Sort.Direction.DESC, "createdAt"));
        List<OrderSummaryResponse> orders = adminOrderService
                .listOrders(q, status, statusGroup, paymentStatus, from, to, pageable, creatorIds, source)
                .getContent();

        List<List<String>> rows = new ArrayList<>(orders.size());
        for (OrderSummaryResponse o : orders) {
            rows.add(List.of(
                    nz(o.orderCode()),
                    nz(o.customerName()),
                    nz(o.customerMobile()),
                    o.orderStatus() == null ? "" : o.orderStatus().name(),
                    o.paymentStatus() == null ? "" : o.paymentStatus().name(),
                    o.source() == null ? "" : o.source().name(),
                    nz(o.salespersonName()),
                    money(o.totalAmount()),
                    money(o.codAmount()),
                    o.createdAt() == null ? "" : o.createdAt().format(DATE_TIME)));
        }
        return new TabularData(HEADERS, rows);
    }

    private static String nz(String v) {
        return v == null ? "" : v;
    }

    private static String money(BigDecimal v) {
        return v == null ? "0.00" : v.toPlainString();
    }
}
