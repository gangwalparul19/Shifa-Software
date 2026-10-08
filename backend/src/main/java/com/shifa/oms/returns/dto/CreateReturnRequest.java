package com.shifa.oms.returns.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Create payload for a return ({@code POST /api/admin/returns}).
 *
 * <p>{@code orderId} accepts EITHER the order's numeric database id (e.g.
 * {@code "5"}, used internally by the order-detail drawer, which already
 * knows it) OR its human-readable order code (e.g. {@code SHR-20260916-JGM9},
 * what an admin actually sees/types on the standalone Returns page) —
 * {@link com.shifa.oms.returns.ReturnService#create} resolves whichever form
 * is supplied.
 *
 * @param orderId the order this return is raised against — numeric id or order code (required)
 * @param reason  the return reason (required)
 * @param notes   optional free-text notes
 */
public record CreateReturnRequest(
        @NotBlank(message = "orderId is required")
        @Size(max = 40, message = "orderId must be at most 40 characters")
        String orderId,

        @NotBlank(message = "reason is required")
        @Size(max = 250, message = "reason must be at most 250 characters")
        String reason,

        String notes
) {
}
