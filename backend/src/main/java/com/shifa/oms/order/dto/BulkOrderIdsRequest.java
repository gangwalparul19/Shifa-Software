package com.shifa.oms.order.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * Request body for the admin bulk order operations (ROADMAP 2.2 "Wave 2"):
 * bulk-approve, bulk-mark-packed, and bulk-labels. Carries the set of order ids
 * the action applies to.
 *
 * @param ids the target order ids (must be non-empty)
 */
public record BulkOrderIdsRequest(
        @NotEmpty(message = "At least one order id is required.")
        List<Long> ids) {
}
