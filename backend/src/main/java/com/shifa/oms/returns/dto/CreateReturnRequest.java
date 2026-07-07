package com.shifa.oms.returns.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Create payload for a return ({@code POST /api/admin/returns}).
 *
 * @param orderId the order this return is raised against (required)
 * @param reason  the return reason (required)
 * @param notes   optional free-text notes
 */
public record CreateReturnRequest(
        @NotNull(message = "orderId is required")
        Long orderId,

        @NotBlank(message = "reason is required")
        @Size(max = 250, message = "reason must be at most 250 characters")
        String reason,

        String notes
) {
}
