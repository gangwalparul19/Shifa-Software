package com.shifa.oms.geo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Admin create/update payload for a delivery state
 * ({@code POST /api/admin/states}, {@code PUT /api/admin/states/{id}}).
 *
 * <p>{@code active} defaults to {@code true} when omitted so a plain create adds
 * a usable state; {@code sortOrder} is optional and defaults to 0 (list falls
 * back to name order).
 */
public record DeliveryStateRequest(
        @NotBlank(message = "name is required")
        @Size(max = 100, message = "name must be at most 100 characters")
        String name,

        Boolean active,

        Integer sortOrder
) {
}
