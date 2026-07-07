package com.shifa.oms.inventory.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/admin/inventory/{productId}/adjust}: apply a
 * signed stock adjustment (positive to add, negative to remove) with an optional
 * reason. A zero delta is rejected.
 */
public record AdjustRequest(
        @NotNull(message = "delta is required")
        Integer delta,

        @Size(max = 255, message = "reason must be at most 255 characters")
        String reason) {
}
