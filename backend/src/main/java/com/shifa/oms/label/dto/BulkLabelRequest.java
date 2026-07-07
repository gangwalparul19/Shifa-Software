package com.shifa.oms.label.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * Request body for bulk internal-label printing (Req 10.4): the set of order ids
 * to include, one internal-label block per id in the returned PDF.
 *
 * @param orderIds the orders to print labels for (must be non-empty)
 */
public record BulkLabelRequest(
        @NotEmpty(message = "At least one order id is required.")
        List<Long> orderIds) {
}
