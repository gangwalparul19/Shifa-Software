package com.shifa.oms.reconciliation.dto;

import com.shifa.oms.order.domain.PaymentStatus;
import com.shifa.oms.statemachine.OrderStatus;

import java.math.BigDecimal;
import java.util.List;

/**
 * Prepaid vs COD segregation of reconciliation-scope orders (Req 18.4).
 *
 * <p>Every order appears in exactly one group: fully-paid orders are prepaid,
 * all others (COD and partially paid) are COD — mirroring the pure-domain
 * {@code ReconciliationLedger.segregate(...)} partition.
 *
 * @param prepaid      the prepaid (fully paid) orders
 * @param cod          the COD / partially-paid orders
 * @param prepaidTotal the summed order total of the prepaid group
 * @param codTotal     the summed order total of the COD group
 */
public record SegregationResponse(
        List<SegregatedOrder> prepaid,
        List<SegregatedOrder> cod,
        BigDecimal prepaidTotal,
        BigDecimal codTotal) {

    /**
     * A single order within a segregation group.
     *
     * @param orderId       the order id
     * @param orderCode     the order code
     * @param customerName  the customer name
     * @param paymentStatus the payment classification
     * @param orderStatus   the lifecycle status
     * @param totalAmount   the net order amount
     * @param codAmount     the COD amount (0 for prepaid)
     */
    public record SegregatedOrder(
            Long orderId,
            String orderCode,
            String customerName,
            PaymentStatus paymentStatus,
            OrderStatus orderStatus,
            BigDecimal totalAmount,
            BigDecimal codAmount) {
    }
}
