package com.shifa.oms.courier.dto;

/**
 * Acknowledgement returned for a courier webhook. {@code applied} indicates
 * whether the update changed the order status; a duplicate or out-of-order
 * update is accepted (HTTP 200) with {@code applied=false} so the courier does
 * not retry needlessly (Req 13.2, idempotency/tolerance).
 *
 * @param applied   whether the update changed the order status
 * @param newStatus the order's status after processing, or {@code null} when no order matched
 */
public record CourierWebhookResponse(boolean applied, String newStatus) {
}
