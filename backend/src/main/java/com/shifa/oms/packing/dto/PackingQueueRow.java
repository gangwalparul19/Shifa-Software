package com.shifa.oms.packing.dto;

import com.shifa.oms.order.OrderEntity;
import com.shifa.oms.order.domain.PaymentStatus;
import com.shifa.oms.statemachine.OrderStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A single row in the packing work queues ({@code GET /api/packing/queue}).
 *
 * <p>Extends the compact order summary with the **order date** and the
 * **salesperson name** (resolved from {@code created_by}) so the packer/admin can
 * see who punched the order and when, directly on the queue (FEATURE request).
 *
 * @param id             order id
 * @param orderCode      order code (the label barcode value)
 * @param customerName   customer name
 * @param salespersonName the salesperson who created the order (nullable)
 * @param totalAmount    order total
 * @param createdAt      when the order was created
 * @param orderStatus    current lifecycle status
 * @param paymentStatus  payment classification
 */
public record PackingQueueRow(
        Long id,
        String orderCode,
        String customerName,
        String salespersonName,
        BigDecimal totalAmount,
        LocalDateTime createdAt,
        OrderStatus orderStatus,
        PaymentStatus paymentStatus
) {

    public static PackingQueueRow from(OrderEntity order, String salespersonName) {
        return new PackingQueueRow(
                order.getId(),
                order.getOrderCode(),
                order.getCustomerName(),
                salespersonName,
                order.getTotalAmount(),
                order.getCreatedAt(),
                order.getOrderStatus(),
                order.getPaymentStatus());
    }
}
