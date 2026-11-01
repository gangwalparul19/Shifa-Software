package com.shifa.oms.analytics;

import com.shifa.oms.order.DeliveryMethod;
import com.shifa.oms.order.LeadSource;
import com.shifa.oms.order.OrderEntity;
import com.shifa.oms.order.OrderSource;
import com.shifa.oms.order.RejectReason;
import com.shifa.oms.order.RtoReason;
import com.shifa.oms.order.domain.PaymentStatus;
import com.shifa.oms.statemachine.OrderStatus;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * Read-only projections the growth analytics are built from. JPQL on
 * {@link OrderEntity}, so deleted orders stay hidden. Each query returns narrow rows
 * (no entities), and the windowed ones take a half-open range {@code [from, to)}.
 */
public interface AnalyticsQueryRepository extends Repository<OrderEntity, Long> {

    /** Every order placed in the window, any status. */
    @Query("""
            select o.id as id, o.orderCode as code, o.createdAt as createdAt,
                   o.customerMobile as mobile, o.customerName as customerName,
                   o.state as state, o.city as city, o.postalCode as postalCode, o.country as country,
                   o.orderStatus as orderStatus, o.paymentStatus as paymentStatus, o.source as orderSource,
                   o.leadSource as leadSource, o.deliveryMethod as deliveryMethod,
                   o.totalAmount as total, o.discountAmount as discount,
                   o.amountReceived as received, o.codAmount as cod, o.createdBy as createdBy,
                   o.rejectReason as rejectReason, o.rtoReason as rtoReason
            from OrderEntity o
            where o.createdAt >= :from and o.createdAt < :to
            order by o.createdAt asc, o.id asc
            """)
    List<OrderRow> orders(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** Every order line of the orders placed in the window, any status. */
    @Query("""
            select o.id as orderId, o.orderCode as orderCode, o.createdAt as createdAt,
                   o.customerMobile as mobile, o.orderStatus as orderStatus,
                   li.productId as productId, li.productName as productName,
                   li.quantity as quantity, li.rate as rate, li.lineTotal as lineTotal
            from OrderEntity o join o.lineItems li
            where o.createdAt >= :from and o.createdAt < :to
            order by o.createdAt asc, o.id asc, li.id asc
            """)
    List<LineRow> lines(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** One row per real customer: sales count, revenue and first / last order time (all time, sales only). */
    @Query("""
            select o.customerMobile as mobile, max(o.customerName) as customerName, count(o) as orders,
                   coalesce(sum(o.totalAmount), 0) as revenue,
                   min(o.createdAt) as firstOrderAt, max(o.createdAt) as lastOrderAt
            from OrderEntity o
            where o.orderStatus not in :lost
              and o.createdAt is not null
              and length(o.customerMobile) = 10
              and o.customerMobile <> :placeholder
            group by o.customerMobile
            """)
    List<CustomerRow> customers(@Param("lost") Collection<OrderStatus> lost,
                                @Param("placeholder") String placeholder);

    /** The first time each order entered each of the given statuses, for orders placed in the window. */
    @Query("""
            select o.id as orderId, h.toStatus as toStatus, min(h.changedAt) as changedAt
            from OrderEntity o join o.statusHistory h
            where o.createdAt >= :from and o.createdAt < :to and h.toStatus in :statuses
            group by o.id, h.toStatus
            """)
    List<StampRow> stamps(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
                          @Param("statuses") Collection<OrderStatus> statuses);

    interface OrderRow {
        Long getId();

        String getCode();

        LocalDateTime getCreatedAt();

        String getMobile();

        String getCustomerName();

        String getState();

        String getCity();

        String getPostalCode();

        String getCountry();

        OrderStatus getOrderStatus();

        PaymentStatus getPaymentStatus();

        OrderSource getOrderSource();

        LeadSource getLeadSource();

        DeliveryMethod getDeliveryMethod();

        BigDecimal getTotal();

        BigDecimal getDiscount();

        BigDecimal getReceived();

        BigDecimal getCod();

        Long getCreatedBy();

        RejectReason getRejectReason();

        RtoReason getRtoReason();
    }

    interface LineRow {
        Long getOrderId();

        String getOrderCode();

        LocalDateTime getCreatedAt();

        String getMobile();

        OrderStatus getOrderStatus();

        Long getProductId();

        String getProductName();

        Integer getQuantity();

        BigDecimal getRate();

        BigDecimal getLineTotal();
    }

    interface CustomerRow {
        String getMobile();

        String getCustomerName();

        Long getOrders();

        BigDecimal getRevenue();

        LocalDateTime getFirstOrderAt();

        LocalDateTime getLastOrderAt();
    }

    interface StampRow {
        Long getOrderId();

        OrderStatus getToStatus();

        LocalDateTime getChangedAt();
    }
}
