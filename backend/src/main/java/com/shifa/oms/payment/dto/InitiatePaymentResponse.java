package com.shifa.oms.payment.dto;

import java.math.BigDecimal;

/**
 * Response for {@code POST /api/payments/initiate}. Carries what the client
 * needs to complete payment plus our own transaction id.
 *
 * @param gateway        the active provider name (e.g. {@code SANDBOX})
 * @param gatewayOrderId the gateway order id to echo back at confirm
 * @param amount         the amount to collect (rupees, DECIMAL(12,2))
 * @param currency       the ISO currency code (e.g. {@code INR})
 * @param keyId          the public key id for a real client widget, or
 *                       {@code null} for the sandbox
 * @param paymentTxnId   our {@code payment_transactions.id} for this attempt
 * @param clientToken    sandbox-only opaque token the client echoes back at
 *                       confirm ({@code null} for a real gateway)
 */
public record InitiatePaymentResponse(
        String gateway,
        String gatewayOrderId,
        BigDecimal amount,
        String currency,
        String keyId,
        Long paymentTxnId,
        String clientToken) {
}
