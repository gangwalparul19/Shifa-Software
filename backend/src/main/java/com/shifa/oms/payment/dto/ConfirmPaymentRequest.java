package com.shifa.oms.payment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Request body for {@code POST /api/payments/confirm}. Mirrors a real hosted
 * checkout callback (order id + payment id + signature), so swapping the sandbox
 * for a real gateway needs no contract change: the provider's widget fills these
 * in directly instead of the sandbox test client.
 *
 * @param orderId          the order being paid for
 * @param gatewayOrderId   the gateway order id from initiate
 * @param gatewayPaymentId the payment id reported by the gateway/client
 * @param signature        the signature over order id + payment id to verify
 */
public record ConfirmPaymentRequest(
        @NotNull(message = "orderId is required")
        Long orderId,

        @NotBlank(message = "gatewayOrderId is required")
        String gatewayOrderId,

        @NotBlank(message = "gatewayPaymentId is required")
        String gatewayPaymentId,

        @NotBlank(message = "signature is required")
        String signature) {
}
