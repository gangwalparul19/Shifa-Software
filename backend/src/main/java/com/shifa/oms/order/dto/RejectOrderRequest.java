package com.shifa.oms.order.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body for {@code POST /api/admin/orders/&#123;id&#125;/reject} (Req 9.4).
 *
 * <p>A non-blank rejection reason is mandatory; a missing or blank reason is
 * rejected with 400 (bean validation) before any status change is attempted, so
 * the order retains its current status.
 */
public record RejectOrderRequest(
        @NotBlank(message = "A rejection reason is required.")
        @Size(max = 500, message = "The rejection reason must be at most 500 characters.")
        String reason
) {
}
