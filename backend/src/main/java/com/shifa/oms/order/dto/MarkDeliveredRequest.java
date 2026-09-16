package com.shifa.oms.order.dto;

import jakarta.validation.constraints.Size;

/**
 * Optional details captured when an in-house-delivery order is manually marked
 * delivered (in-house-delivery feature): a free-text note about the delivery.
 * The body is optional — an absent/empty body still marks the order delivered.
 */
public record MarkDeliveredRequest(
        @Size(max = 500, message = "note must be at most 500 characters")
        String note
) {
}
