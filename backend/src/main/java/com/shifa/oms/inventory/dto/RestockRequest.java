package com.shifa.oms.inventory.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/admin/inventory/{productId}/restock}: add a
 * positive quantity of stock, with an optional reason.
 */
public record RestockRequest(
        @NotNull(message = "quantity is required")
        @Positive(message = "quantity must be positive")
        Integer quantity,

        @Size(max = 255, message = "reason must be at most 255 characters")
        String reason) {
}
