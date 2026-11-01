package com.shifa.oms.incentive;

import com.shifa.oms.order.OrderEntity;
import com.shifa.oms.statemachine.OrderStatus;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * Narrow read-only projections behind the daily sales tracker. JPQL on {@link OrderEntity}, so
 * deleted orders stay hidden. Both queries take a half-open window {@code [from, to)} on the order's
 * creation time, skip orders nobody created (for example Shopify imports), and skip the given
 * non-sale statuses.
 */
public interface IncentiveQueryRepository extends Repository<OrderEntity, Long> {

    /** One row per order. */
    @Query("""
            select o.id as orderId, o.createdAt as createdAt, o.createdBy as createdBy,
                   o.totalAmount as total
            from OrderEntity o
            where o.createdAt >= :from and o.createdAt < :to
              and o.createdBy is not null
              and o.orderStatus not in :lost
            """)
    List<OrderRow> orders(@Param("from") LocalDateTime from,
                          @Param("to") LocalDateTime to,
                          @Param("lost") Collection<OrderStatus> lost);

    /** One row per order line. */
    @Query("""
            select o.id as orderId, o.createdAt as createdAt, o.createdBy as createdBy,
                   li.productId as productId, li.productName as productName,
                   li.quantity as quantity, li.lineTotal as lineTotal
            from OrderEntity o join o.lineItems li
            where o.createdAt >= :from and o.createdAt < :to
              and o.createdBy is not null
              and o.orderStatus not in :lost
            """)
    List<LineRow> lines(@Param("from") LocalDateTime from,
                        @Param("to") LocalDateTime to,
                        @Param("lost") Collection<OrderStatus> lost);

    interface OrderRow {
        Long getOrderId();

        LocalDateTime getCreatedAt();

        Long getCreatedBy();

        BigDecimal getTotal();
    }

    interface LineRow {
        Long getOrderId();

        LocalDateTime getCreatedAt();

        Long getCreatedBy();

        Long getProductId();

        String getProductName();

        Integer getQuantity();

        BigDecimal getLineTotal();
    }
}
