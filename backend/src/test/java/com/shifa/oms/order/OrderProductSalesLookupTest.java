package com.shifa.oms.order;

import com.shifa.oms.product.dto.ProductSalesStatsResponse;
import com.shifa.oms.statemachine.OrderStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Collection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link OrderProductSalesLookup} — the order-module
 * implementation of the product sales-stats inversion (product-detail "Sales
 * Overview"). Verifies the current-month window is computed from the injected
 * clock, the non-revenue statuses (REJECTED/CANCELLED) are excluded, and the
 * aggregate is mapped onto the response (with graceful zero fallbacks).
 */
@ExtendWith(MockitoExtension.class)
class OrderProductSalesLookupTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");

    @Mock
    private OrderRepository orderRepository;

    @Captor
    private ArgumentCaptor<LocalDateTime> startCaptor;
    @Captor
    private ArgumentCaptor<LocalDateTime> endCaptor;
    @Captor
    private ArgumentCaptor<Collection<String>> statusesCaptor;

    /** A fixed clock pinned to 2025-06-15 in the test zone. */
    private Clock fixedClock() {
        return Clock.fixed(
                ZonedDateTime.of(2025, 6, 15, 10, 30, 0, 0, ZONE).toInstant(), ZONE);
    }

    private OrderRepository.ProductSalesAggregate aggregate(BigDecimal revenue, long orders) {
        return new OrderRepository.ProductSalesAggregate() {
            @Override
            public BigDecimal getRevenue() {
                return revenue;
            }

            @Override
            public long getOrderCount() {
                return orders;
            }
        };
    }

    @Test
    void statsForComputesCurrentMonthWindowAndExcludesNonRevenueStatuses() {
        when(orderRepository.productSalesStats(eq(42L), any(), any(), any()))
                .thenReturn(aggregate(new BigDecimal("1234.5"), 7L));
        OrderProductSalesLookup lookup = new OrderProductSalesLookup(orderRepository, fixedClock());

        ProductSalesStatsResponse stats = lookup.statsFor(42L);

        assertThat(stats.salesThisMonth()).isEqualByComparingTo("1234.50");
        assertThat(stats.ordersThisMonth()).isEqualTo(7L);

        verify(orderRepository).productSalesStats(
                eq(42L), startCaptor.capture(), endCaptor.capture(), statusesCaptor.capture());
        // June 2025: [2025-06-01T00:00, 2025-07-01T00:00).
        assertThat(startCaptor.getValue()).isEqualTo(LocalDateTime.of(2025, 6, 1, 0, 0));
        assertThat(endCaptor.getValue()).isEqualTo(LocalDateTime.of(2025, 7, 1, 0, 0));
        assertThat(statusesCaptor.getValue())
                .containsExactlyInAnyOrder(OrderStatus.REJECTED.name(), OrderStatus.CANCELLED.name());
    }

    @Test
    void statsForNullProductIdReturnsZeroWithoutQuerying() {
        OrderProductSalesLookup lookup = new OrderProductSalesLookup(orderRepository, fixedClock());

        ProductSalesStatsResponse stats = lookup.statsFor(null);

        assertThat(stats).isEqualTo(ProductSalesStatsResponse.ZERO);
        assertThat(stats.salesThisMonth()).isEqualByComparingTo("0.00");
        assertThat(stats.ordersThisMonth()).isZero();
        verifyNoInteractions(orderRepository);
    }

    @Test
    void statsForNullAggregateReturnsZero() {
        when(orderRepository.productSalesStats(eq(99L), any(), any(), any())).thenReturn(null);
        OrderProductSalesLookup lookup = new OrderProductSalesLookup(orderRepository, fixedClock());

        ProductSalesStatsResponse stats = lookup.statsFor(99L);

        assertThat(stats.salesThisMonth()).isEqualByComparingTo("0.00");
        assertThat(stats.ordersThisMonth()).isZero();
    }
}
