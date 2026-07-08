package com.shifa.oms.order;

import com.shifa.oms.product.ProductSalesLookup;
import com.shifa.oms.product.dto.ProductSalesStatsResponse;
import com.shifa.oms.statemachine.OrderStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * Order-module implementation of the product {@link ProductSalesLookup}
 * inversion: computes a product's current-month sales stats (revenue + distinct
 * order count) from the order line items, so the product module can surface them
 * on the product-detail "Sales Overview" without depending on the order module.
 *
 * <p>Revenue excludes orders that never produced sellable revenue — the same
 * {@link OrderStatus#REJECTED} / {@link OrderStatus#CANCELLED} exclusion the
 * P&amp;L revenue uses. The month window is {@code [firstOfMonth 00:00,
 * firstOfNextMonth 00:00)} in the service clock's zone.
 */
@Service
public class OrderProductSalesLookup implements ProductSalesLookup {

    /**
     * Order statuses that never count towards revenue (mirrors the P&amp;L
     * {@code NON_REVENUE_STATUSES}); stored as enum names for the native query
     * since {@code order_status} is persisted as a string.
     */
    private static final Set<String> NON_REVENUE_STATUS_NAMES =
            Set.of(OrderStatus.REJECTED.name(), OrderStatus.CANCELLED.name());

    private final OrderRepository orderRepository;
    private final Clock clock;

    @Autowired
    public OrderProductSalesLookup(OrderRepository orderRepository) {
        this(orderRepository, Clock.systemDefaultZone());
    }

    /** Package-visible constructor allowing a fixed clock in tests. */
    OrderProductSalesLookup(OrderRepository orderRepository, Clock clock) {
        this.orderRepository = orderRepository;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public ProductSalesStatsResponse statsFor(Long productId) {
        if (productId == null) {
            return ProductSalesStatsResponse.ZERO;
        }
        LocalDate firstOfMonth = LocalDate.now(clock).withDayOfMonth(1);
        LocalDateTime startInclusive = firstOfMonth.atStartOfDay();
        LocalDateTime endExclusive = firstOfMonth.plusMonths(1).atStartOfDay();

        OrderRepository.ProductSalesAggregate aggregate = orderRepository.productSalesStats(
                productId, startInclusive, endExclusive, List.copyOf(NON_REVENUE_STATUS_NAMES));
        if (aggregate == null) {
            return ProductSalesStatsResponse.ZERO;
        }
        BigDecimal revenue = aggregate.getRevenue() != null
                ? aggregate.getRevenue().setScale(2, java.math.RoundingMode.HALF_UP)
                : BigDecimal.ZERO.setScale(2);
        return new ProductSalesStatsResponse(revenue, aggregate.getOrderCount());
    }
}
