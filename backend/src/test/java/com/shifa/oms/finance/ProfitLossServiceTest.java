package com.shifa.oms.finance;

import com.shifa.oms.common.ValidationException;
import com.shifa.oms.finance.dto.ProfitLossResponse;
import com.shifa.oms.order.OrderEntity;
import com.shifa.oms.order.OrderRepository;
import com.shifa.oms.order.OrderSource;
import com.shifa.oms.reconciliation.ReceivableEntity;
import com.shifa.oms.reconciliation.ReceivableRepository;
import com.shifa.oms.reconciliation.domain.ReceivableType;
import com.shifa.oms.statemachine.OrderStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Example-based unit tests for {@link ProfitLossService} (Feature C3). All three
 * repositories are Mockito mocks returning fixed rows so the test asserts the
 * summation / netProfit logic and the documented revenue/cost definitions.
 */
@ExtendWith(MockitoExtension.class)
class ProfitLossServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private ReceivableRepository receivableRepository;

    @Mock
    private ExpenseRepository expenseRepository;

    private ProfitLossService service;

    private static final LocalDate FROM = LocalDate.of(2024, 1, 1);
    private static final LocalDate TO = LocalDate.of(2024, 1, 31);

    @BeforeEach
    void setUp() {
        service = new ProfitLossService(orderRepository, receivableRepository, expenseRepository);
    }

    @Test
    void reportSumsRevenueExcludesRejectedCancelledAndComputesNetProfit() {
        when(orderRepository.findByCreatedAtBetween(any(), any())).thenReturn(List.of(
                order("1000.00", OrderStatus.DELIVERED),
                order("500.00", OrderStatus.CANCELLED),   // excluded
                order("750.00", OrderStatus.REJECTED),    // excluded
                order("300.00", OrderStatus.APPROVED)));

        when(receivableRepository.findByCreatedAtBetween(any(), any())).thenReturn(List.of(
                claim("200.00", false),   // outstanding
                claim("100.00", true),    // recovered
                cod("400.00", false)));   // cod outstanding

        when(expenseRepository.findByIncurredOnBetween(any(), any())).thenReturn(List.of(
                expense("RENT", "150.00"),
                expense("SALARIES", "500.00"),
                expense("RENT", "250.00")));

        ProfitLossResponse report = service.report(FROM, TO);

        assertThat(report.revenue()).isEqualByComparingTo("1300.00");   // 1000 + 300
        assertThat(report.claimsOutstanding()).isEqualByComparingTo("200.00");
        assertThat(report.courierCost()).isEqualByComparingTo("200.00"); // == claimsOutstanding
        assertThat(report.claimsRecovered()).isEqualByComparingTo("100.00");
        assertThat(report.codOutstanding()).isEqualByComparingTo("400.00");
        assertThat(report.totalExpenses()).isEqualByComparingTo("900.00"); // 150 + 500 + 250

        // netProfit = revenue - courierCost - totalExpenses = 1300 - 200 - 900 = 200
        assertThat(report.netProfit()).isEqualByComparingTo("200.00");

        // Per-category breakdown groups the two RENT rows.
        assertThat(report.expenseByCategory())
                .anySatisfy(c -> {
                    assertThat(c.category()).isEqualTo("RENT");
                    assertThat(c.amount()).isEqualByComparingTo("400.00");
                })
                .anySatisfy(c -> {
                    assertThat(c.category()).isEqualTo("SALARIES");
                    assertThat(c.amount()).isEqualByComparingTo("500.00");
                });
    }

    @Test
    void reportWithEmptyDataIsAllZero() {
        when(orderRepository.findByCreatedAtBetween(any(), any())).thenReturn(List.of());
        when(receivableRepository.findByCreatedAtBetween(any(), any())).thenReturn(List.of());
        when(expenseRepository.findByIncurredOnBetween(any(), any())).thenReturn(List.of());

        ProfitLossResponse report = service.report(FROM, TO);

        assertThat(report.revenue()).isEqualByComparingTo("0.00");
        assertThat(report.netProfit()).isEqualByComparingTo("0.00");
        assertThat(report.expenseByCategory()).isEmpty();
    }

    @Test
    void reportRejectsInvertedWindow() {
        assertThatThrownBy(() -> service.report(TO, FROM))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("must not be before");
    }

    // --- fixtures ------------------------------------------------------------

    private OrderEntity order(String total, OrderStatus status) {
        OrderEntity o = new OrderEntity("SHR-1", OrderSource.STOREFRONT, null,
                "Asha", "9812345678", "12 MG Road", "Pune", "Maharashtra", "411001");
        o.applyAmounts(new BigDecimal(total), BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, null);
        o.setOrderStatus(status);
        return o;
    }

    private ReceivableEntity claim(String amount, boolean settled) {
        ReceivableEntity r = new ReceivableEntity(1L, 2L, ReceivableType.CLAIM_RECEIVABLE,
                new BigDecimal(amount));
        if (settled) {
            r.settle(LocalDate.of(2024, 1, 15));
        }
        return r;
    }

    private ReceivableEntity cod(String amount, boolean settled) {
        ReceivableEntity r = new ReceivableEntity(1L, 2L, ReceivableType.COD_RECEIVABLE,
                new BigDecimal(amount));
        if (settled) {
            r.settle(LocalDate.of(2024, 1, 15));
        }
        return r;
    }

    private Expense expense(String category, String amount) {
        return new Expense(category, null, new BigDecimal(amount),
                LocalDate.of(2024, 1, 10), null);
    }
}
