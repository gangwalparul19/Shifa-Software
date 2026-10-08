package com.shifa.oms.order.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body for {@code POST /api/admin/orders/&#123;id&#125;/cancel} (order-cancellation
 * feature).
 *
 * <p>A non-blank cancellation note is mandatory — a missing or blank note is
 * rejected with 400 (bean validation) before any status change is attempted, so
 * the order retains its current status. The note documents WHY the order was
 * cancelled (e.g. "Payment never received", "Customer cancelled after part
 * payment — ₹200 refund due") and is stored on the order + the audit trail, so
 * everyone can see the reason — and, for a courier order, so does the follow-up
 * if the courier-side cancellation needs a manual check.
 */
public record CancelOrderRequest(
        @NotBlank(message = "A cancellation note is required.")
        @Size(max = 500, message = "The cancellation note must be at most 500 characters.")
        String note
) {
}
