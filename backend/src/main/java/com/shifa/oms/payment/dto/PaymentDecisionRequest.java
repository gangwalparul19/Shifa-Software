package com.shifa.oms.payment.dto;

import jakarta.validation.constraints.Size;

/**
 * A Payment Verifier's decision note when verifying or rejecting a payment
 * (product-audit §4.4). The note is optional (a verify often needs none); a
 * reject should carry a reason but it is not enforced server-side.
 */
public record PaymentDecisionRequest(
        @Size(max = 500, message = "note must be at most 500 characters")
        String note
) {
}
