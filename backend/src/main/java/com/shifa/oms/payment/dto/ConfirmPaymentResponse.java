package com.shifa.oms.payment.dto;

/**
 * Response for {@code POST /api/payments/confirm}: whether the order is now paid
 * and its human-readable order code (for the confirmation screen). On a failed
 * verification {@code paid} is {@code false} and the order is unchanged.
 */
public record ConfirmPaymentResponse(boolean paid, String orderCode) {
}
