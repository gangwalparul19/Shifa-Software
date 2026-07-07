package com.shifa.oms.payment.dto;

import jakarta.validation.constraints.NotNull;

/**
 * Request body for {@code POST /api/payments/initiate}: the order to pay for.
 */
public record InitiatePaymentRequest(
        @NotNull(message = "orderId is required")
        Long orderId) {
}
