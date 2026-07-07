package com.shifa.oms.courier.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * Request body for bulk courier shipping-label printing (Req 12.5): the set of
 * order ids to include. One shipping-label block is produced per requested order
 * that has an assigned AWB; orders without an AWB are skipped.
 *
 * @param orderIds the orders to print shipping labels for (must be non-empty)
 */
public record BulkShippingLabelRequest(
        @NotEmpty(message = "At least one order id is required.")
        List<Long> orderIds) {
}
