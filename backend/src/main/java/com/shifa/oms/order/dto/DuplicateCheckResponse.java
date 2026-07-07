package com.shifa.oms.order.dto;

/**
 * Result of {@code GET /api/orders/duplicate-check?mobile=} (Req 22.2):
 * whether one or more prior orders exist for the mobile number, and how many.
 */
public record DuplicateCheckResponse(String mobile, boolean hasPriorOrders, long priorOrderCount) {
}
