package com.shifa.oms.reporting;

import com.shifa.oms.courier.CourierRecord;
import com.shifa.oms.courier.CourierRecordRepository;
import com.shifa.oms.order.OrderEntity;
import com.shifa.oms.order.OrderLineItem;
import com.shifa.oms.order.OrderRepository;
import com.shifa.oms.order.OrderSource;
import com.shifa.oms.order.domain.PaymentStatus;
import com.shifa.oms.reconciliation.ReceivableEntity;
import com.shifa.oms.reconciliation.ReceivableRepository;
import com.shifa.oms.reconciliation.domain.ReceivableType;
import com.shifa.oms.reporting.domain.ReportType;
import com.shifa.oms.reporting.dto.ReportResponse;
import com.shifa.oms.statemachine.OrderStatus;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the report loader against the N+1 that made a month-long report take
 * 20+ seconds: it used to run four queries per order (lazy line items, courier
 * record, COD receivable, claim receivable), i.e. ~9,000 round-trips for 2,300
 * orders. The loader now batches all of that, so the statement count must not
 * grow with the number of orders, AND every order must still receive its own
 * AWB / COD status / claim status (batching can easily mix those up).
 *
 * <p>Runs the real {@link ReportService} against an isolated in-memory H2
 * database with Hibernate statistics on. Statements are counted as the
 * <em>minimum over several runs</em> so a stray background job that happens to
 * tick during one run cannot make the test flaky.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:report_query_count;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.jpa.properties.hibernate.generate_statistics=true"
})
@ActiveProfiles("smoketest")
class ReportQueryCountTest {

    private static final LocalDate FROM = LocalDate.of(2026, 10, 1);
    private static final LocalDate TO = LocalDate.of(2026, 10, 31);

    /** Generous ceiling for the whole report; the N+1 version needs ~4 statements per order. */
    private static final int MAX_STATEMENTS = 25;

    @Autowired private ReportService reportService;
    @Autowired private OrderRepository orderRepository;
    @Autowired private CourierRecordRepository courierRecordRepository;
    @Autowired private ReceivableRepository receivableRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManagerFactory entityManagerFactory;
    @Autowired private PlatformTransactionManager transactionManager;

    private Statistics statistics;

    @BeforeEach
    void resetData() {
        statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        receivableRepository.deleteAll();
        courierRecordRepository.deleteAll();
        orderRepository.deleteAll();
    }

    @Test
    void statementCountStaysFlatAsTheNumberOfOrdersGrows() {
        seed(0, 6);
        long small = statementsForReport();

        seed(6, 60);
        long large = statementsForReport();

        assertThat(small).as("statements for 6 orders").isLessThanOrEqualTo(MAX_STATEMENTS);
        assertThat(large).as("statements for 60 orders").isLessThanOrEqualTo(MAX_STATEMENTS);
        // 10x the orders must not cost meaningfully more statements.
        assertThat(large).as("statements must not scale with order count").isLessThanOrEqualTo(small + 3);
    }

    @Test
    void everyOrderStillGetsItsOwnCourierReceivableAndClaimStatus() {
        seed(0, 30); // 30 = lcm(2, 3, 5), so every combination of the three conditions occurs

        ReportResponse report = reportService.generate(ReportType.SALESPERSON, FROM, TO);

        List<String> headers = report.headers();
        int customer = headers.indexOf("Customer Name");
        int cod = headers.indexOf("On-Delivery Collection Status");
        int claim = headers.indexOf("Loss Claim Status");
        int awb = headers.indexOf("AWB");
        int products = headers.indexOf("Products");
        assertThat(report.rows()).hasSize(30);

        Map<String, List<String>> byCustomer = new HashMap<>();
        for (List<String> row : report.rows()) {
            byCustomer.put(row.get(customer), row);
        }
        for (int i = 0; i < 30; i++) {
            List<String> row = byCustomer.get("Cust-" + i);
            assertThat(row).as("row for Cust-" + i).isNotNull();
            assertThat(row.get(awb)).as("AWB of order " + i).isEqualTo(i % 2 == 0 ? "AWB-" + i : "");
            assertThat(row.get(cod)).as("COD status of order " + i).isEqualTo(expectedCodStatus(i));
            assertThat(row.get(claim)).as("claim status of order " + i).isEqualTo(expectedClaimStatus(i));
            // Line items must come through for each order, in line order.
            assertThat(row.get(products)).as("products of order " + i)
                    .isEqualTo("Prod-" + i + " x1, Common x2");
        }

        // Summary money tile: COD still pending with the courier (orders with an unsettled COD receivable).
        long pendingOrders = 0;
        for (int i = 0; i < 30; i++) {
            if ("Pending".equals(expectedCodStatus(i))) {
                pendingOrders++;
            }
        }
        assertThat(report.summary().codPendingFromCourier())
                .isEqualByComparingTo(BigDecimal.valueOf(100L * pendingOrders));
    }

    // --- fixtures -------------------------------------------------------------

    private static String expectedCodStatus(int i) {
        if (i % 3 != 0) {
            return "N/A";
        }
        return i % 6 == 0 ? "Received-from-courier" : "Pending";
    }

    private static String expectedClaimStatus(int i) {
        if (i % 5 != 0) {
            return "N/A";
        }
        return i % 10 == 0 ? "Received" : "Pending";
    }

    /** Seeds orders {@code fromInclusive..toExclusive-1}, each with 2 lines and conditional courier/receivables. */
    private void seed(int fromInclusive, int toExclusive) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            for (int i = fromInclusive; i < toExclusive; i++) {
                OrderEntity order = new OrderEntity(String.format("T-%04d", i), OrderSource.SALESPERSON, 5L,
                        "Cust-" + i, "98" + String.format("%08d", i), "12 MG Road", "Pune", "Maharashtra", "411001");
                boolean hasCod = i % 3 == 0;
                BigDecimal cod = hasCod ? new BigDecimal("100.00") : BigDecimal.ZERO;
                BigDecimal total = new BigDecimal("300.00");
                BigDecimal received = total.subtract(cod);
                order.applyAmounts(total, received, cod, cod,
                        hasCod ? PaymentStatus.PARTIALLY_PAID : PaymentStatus.FULLY_PAID);
                order.setOrderStatus(OrderStatus.DELIVERED);
                order.addLineItem(new OrderLineItem((long) i, "Prod-" + i, 1, new BigDecimal("100.00"), new BigDecimal("100.00")));
                order.addLineItem(new OrderLineItem(9999L, "Common", 2, new BigDecimal("100.00"), new BigDecimal("200.00")));
                OrderEntity saved = orderRepository.saveAndFlush(order);
                Long id = saved.getId();

                if (i % 2 == 0) {
                    CourierRecord record = new CourierRecord(id);
                    record.assign(1L, "AWB-" + i, null, null);
                    courierRecordRepository.save(record);
                }
                if (hasCod) {
                    ReceivableEntity r = new ReceivableEntity(id, 1L, ReceivableType.COD_RECEIVABLE, cod);
                    if (i % 6 == 0) {
                        r.settle(LocalDate.of(2026, 10, 20));
                    }
                    receivableRepository.save(r);
                }
                if (i % 5 == 0) {
                    ReceivableEntity r = new ReceivableEntity(id, 1L, ReceivableType.CLAIM_RECEIVABLE, new BigDecimal("50.00"));
                    if (i % 10 == 0) {
                        r.settle(LocalDate.of(2026, 10, 21));
                    }
                    receivableRepository.save(r);
                }
            }
        });
        // created_at is database-generated in production; H2 has no default, so stamp it here.
        jdbc.update("UPDATE orders SET created_at = ? WHERE created_at IS NULL",
                java.sql.Timestamp.valueOf("2026-10-15 10:00:00"));
    }

    /** Minimum statement count over several runs, so an unrelated background job cannot inflate it. */
    private long statementsForReport() {
        long best = Long.MAX_VALUE;
        for (int run = 0; run < 4; run++) {
            statistics.clear();
            reportService.generate(ReportType.DAILY, FROM, TO);
            best = Math.min(best, statistics.getPrepareStatementCount());
        }
        return best;
    }
}
