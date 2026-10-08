package com.shifa.oms.packing.dto;

import com.shifa.oms.order.RtoReason;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/packing/{id}/rto} (label redesign
 * feature): a packer/admin scans an order's label to manually mark it RTO
 * (returned to origin), required to pick a categorized reason plus an optional
 * free-text note (mainly meaningful for {@link RtoReason#OTHER}).
 */
public record MarkRtoRequest(
        @NotNull(message = "reason is required")
        RtoReason reason,

        @Size(max = 500, message = "note must be at most 500 characters")
        String note
) {
}
