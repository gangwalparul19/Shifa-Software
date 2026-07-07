package com.shifa.oms.order.dto;

import com.shifa.oms.order.OrderEntity;

/**
 * Confirmation returned by {@code POST /api/checkout} (Req 3.7): the created
 * order's id and its human-readable order code, shown to the customer.
 */
public record CheckoutResponse(Long id, String orderCode) {

    public static CheckoutResponse from(OrderEntity order) {
        return new CheckoutResponse(order.getId(), order.getOrderCode());
    }
}
