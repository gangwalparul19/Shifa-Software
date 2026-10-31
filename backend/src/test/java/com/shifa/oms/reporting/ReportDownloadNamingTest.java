package com.shifa.oms.reporting;

import com.shifa.oms.auth.SecurityConfig;
import com.shifa.oms.order.AdminOrderService;
import com.shifa.oms.order.OrderExportService;
import com.shifa.oms.order.OrderSource;
import com.shifa.oms.order.OrderStatusGroup;
import com.shifa.oms.order.domain.PaymentStatus;
import com.shifa.oms.order.dto.OrderSummaryResponse;
import com.shifa.oms.reporting.domain.ReportType;
import com.shifa.oms.reporting.domain.TabularData;
import com.shifa.oms.reporting.dto.ReportResponse;
import com.shifa.oms.reporting.dto.ReportSummary;
import com.shifa.oms.statemachine.OrderStatus;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end check, through the real controllers and exporters, that every
 * downloaded report carries its own descriptive filename instead of a generic
 * {@code report.pdf}, and that the browser is allowed to read that name.
 */
class ReportDownloadNamingTest {

    private static final LocalDate FROM = LocalDate.of(2026, 10, 2);
    private static final LocalDate TO = LocalDate.of(2026, 10, 31);

    private final ReportController controller = new ReportController(
            new CannedReportService(), new ExcelReportExporter(), new PdfReportExporter(), new CsvReportExporter());

    @Test
    void everyReportTypeDownloadsUnderItsOwnNameInBothFormats() {
        Set<String> names = new HashSet<>();
        for (ReportType type : ReportType.values()) {
            for (String format : List.of("xlsx", "pdf")) {
                String name = filenameOf(controller.export(type.name(), format, FROM, TO));

                assertThat(name).as(type + " " + format)
                        .matches("shifa-[a-z0-9-]+_2026-10-02_to_2026-10-31\\." + format)
                        .doesNotStartWith("report");
                names.add(name);
            }
        }
        // 2 formats x every type, no two sharing a name.
        assertThat(names).hasSize(ReportType.values().length * 2);
    }

    @Test
    void namesTheReportAndItsDateRange() {
        assertThat(filenameOf(controller.export("daily", "pdf", FROM, TO)))
                .isEqualTo("shifa-daily-sales-report_2026-10-02_to_2026-10-31.pdf");
        assertThat(filenameOf(controller.export("customer", "xlsx", null, null)))
                .isEqualTo("shifa-customer-wise-report_all-time.xlsx");
        assertThat(filenameOf(controller.export("executive-product-matrix", "xlsx", FROM, null)))
                .isEqualTo("shifa-sales-executive-product-tracker_from-2026-10-02.xlsx");
    }

    @Test
    void theDownloadHeaderCarriesTheNameInAFormABrowserReads() {
        ResponseEntity<byte[]> response = controller.export("daily", "pdf", FROM, TO);

        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .isEqualTo("attachment; filename=\"shifa-daily-sales-report_2026-10-02_to_2026-10-31.pdf\"");
        assertThat(response.getHeaders().getContentDisposition().isAttachment()).isTrue();
    }

    @Test
    void vyaparExportsAreNamedForTheirDateRangeToo() {
        assertThat(filenameOf(controller.vyapar(FROM, TO, "csv")))
                .isEqualTo("shifa-vyapar-billing_2026-10-02_to_2026-10-31.csv");
        assertThat(filenameOf(controller.vyapar(FROM, TO, "xlsx")))
                .isEqualTo("shifa-vyapar-billing_2026-10-02_to_2026-10-31.xlsx");
    }

    @Test
    void theOrdersListExportIsNamedForItsDateRange() {
        OrderExportService orders = new OrderExportService(
                new EmptyOrdersService(), new CsvReportExporter(), new ExcelReportExporter());

        OrderExportService.ExportResult xlsx = orders.export(
                null, null, null, null, FROM, TO, null, null, null, "xlsx");
        OrderExportService.ExportResult csv = orders.export(
                null, null, OrderStatusGroup.PROCESSING, PaymentStatus.COD, null, null, null, null, null, "csv");

        assertThat(xlsx.filename()).isEqualTo("shifa-orders_2026-10-02_to_2026-10-31.xlsx");
        assertThat(csv.filename()).isEqualTo("shifa-orders_all-time.csv");
    }

    @Test
    void theBrowserIsAllowedToReadTheFilenameAndTheVyaparNotice() {
        UrlBasedCorsConfigurationSource source =
                (UrlBasedCorsConfigurationSource) new SecurityConfig().corsConfigurationSource();
        CorsConfiguration config = source.getCorsConfigurations().get("/**");

        // Without this a cross-origin page (the dev server) cannot read the name and saves "report.pdf".
        assertThat(config.getExposedHeaders())
                .contains(HttpHeaders.CONTENT_DISPOSITION, "X-Report-Message", "Authorization");
    }

    private static String filenameOf(ResponseEntity<byte[]> response) {
        return response.getHeaders().getContentDisposition().getFilename();
    }

    /** Returns a one-row report for any type so the real exporters render real files. */
    private static final class CannedReportService extends ReportService {
        CannedReportService() {
            super(null, null, null, null, null, null, null, null);
        }

        @Override
        public ReportResponse generate(ReportType type, LocalDate from, LocalDate to) {
            ReportSummary summary = new ReportSummary(
                    BigDecimal.ZERO, 0, false, BigDecimal.ZERO, null, null, null, null,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
            return new ReportResponse(type.name(), from, to, List.of("Name", "Orders"),
                    List.of(List.of("Asha", "3")), summary);
        }

        @Override
        public TabularData vyaparTable(LocalDate from, LocalDate to) {
            return new TabularData(List.of("Invoice"), List.of(List.of("INV-1")));
        }
    }

    /** An orders source that yields no rows, so the export builds without a database. */
    private static final class EmptyOrdersService extends AdminOrderService {
        EmptyOrdersService() {
            super(null, null, null, null);
        }

        @Override
        public Page<OrderSummaryResponse> listOrders(
                String q, OrderStatus status, OrderStatusGroup statusGroup, PaymentStatus paymentStatus,
                LocalDate from, LocalDate to, Pageable pageable, Collection<Long> creatorIds,
                OrderSource source, OrderSource excludeSource) {
            return Page.empty(pageable);
        }
    }
}
