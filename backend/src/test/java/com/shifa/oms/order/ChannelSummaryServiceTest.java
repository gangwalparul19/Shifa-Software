package com.shifa.oms.order;

import com.shifa.oms.order.domain.PaymentStatus;
import com.shifa.oms.order.dto.ChannelSummaryResponse;
import com.shifa.oms.statemachine.OrderStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ChannelSummaryService}: splitting order metrics by
 * channel (portal vs Shopify vs total), excluding rejected/cancelled from
 * revenue, summing COD outstanding, computing this-month figures against a fixed
 * clock, and grouping the status breakdown.
 */
class ChannelSummaryServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
    // Fixed "now" = 15 Jun 2026, so "this month" = June 2026.
    private static final Clock CLOCK = Clock.fixed(
            LocalDate.of(2026, 6, 15).atStartOfDay(ZONE).toInstant(), ZONE);

    private OrderRepository orderRepository;
    private ChannelSummaryService service;

    @BeforeEach
    void setUp() {
        orderRepository = mock(OrderRepository.class);
        service = new ChannelSummaryService(orderRepository, CLOCK);
    }

    @Test
    void splitsByChannelExcludesNonRevenueAndSumsCodOutstanding() {
        List<OrderEntity> orders = List.of(
                // Portal orders
                order(OrderSource.SALESPERSON, OrderStatus.DELIVERED, "1000.00", "0.00", 2026, 6, 10),
                order(OrderSource.STOREFRONT, OrderStatus.COURIER_ASSIGNED, "500.00", "500.00", 2026, 6, 12),
                order(OrderSource.SALESPERSON, OrderStatus.REJECTED, "999.00", "0.00", 2026, 6, 5), // excluded from revenue
                // Shopify orders
                order(OrderSource.SHOPIFY, OrderStatus.LABEL_GENERATED, "2200.00", "0.00", 2026, 6, 11),
                order(OrderSource.SHOPIFY, OrderStatus.COURIER_ASSIGNED, "800.00", "300.00", 2026, 5, 20)); // last month
        when(orderRepository.findByCreatedAtBetween(any(), any())).thenReturn(orders);

        ChannelSummaryResponse res = service.summary(LocalDate.of(2026, 5, 1), LocalDate.of(2026, 6, 30));

        // Total: 5 orders; revenue excludes the rejected 999 => 1000+500+2200+800 = 4500.
        assertThat(res.total().orderCount()).isEqualTo(5);
        assertThat(res.total().revenue()).isEqualByComparingTo("4500.00");
        assertThat(res.total().codOutstanding()).isEqualByComparingTo("800.00"); // 500 + 300

        // Portal: 3 orders (2 revenue), revenue 1500, COD outstanding 500.
        assertThat(res.portal().orderCount()).isEqualTo(3);
        assertThat(res.portal().revenue()).isEqualByComparingTo("1500.00");
        assertThat(res.portal().codOutstanding()).isEqualByComparingTo("500.00");

        // Shopify: 2 orders, revenue 3000, COD outstanding 300.
        assertThat(res.shopify().orderCount()).isEqualTo(2);
        assertThat(res.shopify().revenue()).isEqualByComparingTo("3000.00");
        assertThat(res.shopify().codOutstanding()).isEqualByComparingTo("300.00");
    }

    @Test
    void thisMonthCountsOnlyCurrentMonthOrders() {
        List<OrderEntity> orders = List.of(
                order(OrderSource.SHOPIFY, OrderStatus.DELIVERED, "2200.00", "0.00", 2026, 6, 11), // this month
                order(OrderSource.SHOPIFY, OrderStatus.DELIVERED, "800.00", "0.00", 2026, 5, 20));  // last month
        when(orderRepository.findByCreatedAtBetween(any(), any())).thenReturn(orders);

        ChannelSummaryResponse res = service.summary(LocalDate.of(2026, 5, 1), LocalDate.of(2026, 6, 30));

        assertThat(res.shopify().orderCount()).isEqualTo(2);
        assertThat(res.shopify().monthOrderCount()).isEqualTo(1);       // only the June order
        assertThat(res.shopify().monthRevenue()).isEqualByComparingTo("2200.00");
    }

    @Test
    void statusBreakdownFoldsIntoBusinessGroupsInLifecycleOrder() {
        List<OrderEntity> orders = List.of(
                order(OrderSource.SHOPIFY, OrderStatus.PENDING_ADMIN_APPROVAL, "100.00", "0.00", 2026, 6, 1),
                order(OrderSource.SHOPIFY, OrderStatus.LABEL_GENERATED, "100.00", "0.00", 2026, 6, 2),
                order(OrderSource.SHOPIFY, OrderStatus.COURIER_ASSIGNED, "100.00", "0.00", 2026, 6, 3),
                order(OrderSource.SHOPIFY, OrderStatus.DELIVERED, "100.00", "0.00", 2026, 6, 4));
        when(orderRepository.findByCreatedAtBetween(any(), any())).thenReturn(orders);

        ChannelSummaryResponse res = service.summary(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30));

        // 4 distinct groups, emitted in lifecycle order.
        assertThat(res.shopify().statusBreakdown())
                .extracting(ChannelSummaryResponse.StatusCount::group)
                .containsExactly("PENDING_APPROVAL", "PROCESSING", "SHIPPED", "DELIVERED");
        assertThat(res.shopify().statusBreakdown())
                .allSatisfy(sc -> assertThat(sc.count()).isEqualTo(1));
    }

    @Test
    void allTimeWindowLoadsEveryOrderViaFindAll() {
        List<OrderEntity> orders = List.of(
                order(OrderSource.SALESPERSON, OrderStatus.DELIVERED, "300.00", "0.00", 2025, 1, 1),
                order(OrderSource.SHOPIFY, OrderStatus.DELIVERED, "700.00", "0.00", 2026, 6, 1));
        when(orderRepository.findAll()).thenReturn(orders);

        ChannelSummaryResponse res = service.summary(null, null);

        assertThat(res.from()).isNull();
        assertThat(res.to()).isNull();
        assertThat(res.total().orderCount()).isEqualTo(2);
        assertThat(res.total().revenue()).isEqualByComparingTo("1000.00");
        assertThat(res.portal().orderCount()).isEqualTo(1);
        assertThat(res.shopify().orderCount()).isEqualTo(1);
    }

    private static OrderEntity order(OrderSource source, OrderStatus status, String total,
                                     String outstanding, int year, int month, int day) {
        OrderEntity order = new OrderEntity(
                "SHR-" + source + "-" + year + month + day, source, null,
                "Asha", "9812345678", "12 MG Road", "Pune", "Maharashtra", "411001");
        BigDecimal totalAmt = new BigDecimal(total);
        BigDecimal cod = new BigDecimal(outstanding);
        order.applyAmounts(totalAmt, totalAmt.subtract(cod), cod, cod, PaymentStatus.COD);
        order.setCustomerOutstanding(cod);
        order.setOrderStatus(status);
        ReflectionTestUtils.setField(order, "createdAt", LocalDateTime.of(year, month, day, 10, 0));
        return order;
    }
}
