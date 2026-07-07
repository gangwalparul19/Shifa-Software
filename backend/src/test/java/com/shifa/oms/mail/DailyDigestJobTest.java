package com.shifa.oms.mail;

import com.shifa.oms.statemachine.OrderStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the pure digest-body builder in {@link DailyDigestJob}
 * (Feature C4). Exercised without the scheduler, the DB, or Mockito by feeding
 * plain {@link DigestOrder} records to
 * {@link DailyDigestJob#buildDigestBody(List, LocalDate)}.
 */
class DailyDigestJobTest {

    private final DailyDigestJob job = new DailyDigestJob(null, null, null, null);

    private static DigestOrder prepaid(String amount) {
        return new DigestOrder(OrderStatus.APPROVED, new BigDecimal(amount),
                BigDecimal.ZERO, "Cust", "MH");
    }

    private static DigestOrder cod(String amount, OrderStatus status) {
        return new DigestOrder(status, new BigDecimal(amount),
                new BigDecimal(amount), "Cust", "MH");
    }

    @Test
    void countsOrdersAndTotalsExcludingRejectedAndCancelled() {
        LocalDate day = LocalDate.of(2024, 5, 10);
        List<DigestOrder> orders = List.of(
                prepaid("100.00"),
                cod("250.00", OrderStatus.DISPATCHED),
                cod("999.00", OrderStatus.REJECTED),
                prepaid("50.00"),
                cod("500.00", OrderStatus.CANCELLED));

        String body = job.buildDigestBody(orders, day);

        // 3 qualifying orders (2 prepaid + 1 cod); total = 100 + 250 + 50 = 400
        assertThat(body).contains("Orders: 3");
        assertThat(body).contains("Total sales: 400.00");
        // rejected/cancelled totals must not appear
        assertThat(body).doesNotContain("999.00");
        assertThat(body).doesNotContain("500.00");
        assertThat(body).contains("Excluded (rejected/cancelled): 2");
        assertThat(body).contains("2024-05-10");
    }

    @Test
    void splitsPrepaidAndCod() {
        LocalDate day = LocalDate.of(2024, 5, 10);
        List<DigestOrder> orders = List.of(
                prepaid("100.00"),
                prepaid("100.00"),
                cod("250.00", OrderStatus.DISPATCHED));

        String body = job.buildDigestBody(orders, day);

        assertThat(body).contains("Prepaid: 2 order(s), 200.00");
        assertThat(body).contains("COD: 1 order(s), 250.00");
    }

    @Test
    void emptyDayStillProducesReadableBody() {
        LocalDate day = LocalDate.of(2024, 5, 10);

        String body = job.buildDigestBody(List.of(), day);

        assertThat(body).contains("No orders yesterday.");
        assertThat(body).contains("2024-05-10");
    }

    @Test
    void allOrdersRejectedReportsNoSales() {
        LocalDate day = LocalDate.of(2024, 5, 10);
        List<DigestOrder> orders = List.of(
                cod("999.00", OrderStatus.REJECTED),
                cod("500.00", OrderStatus.CANCELLED));

        String body = job.buildDigestBody(orders, day);

        assertThat(body).contains("No orders yesterday.");
        assertThat(body).contains("Excluded (rejected/cancelled): 2");
    }
}
