package com.shifa.oms.procurement.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * A single receipt line: how many units of a PO line item arrived in this
 * receiving event.
 */
public record ReceiveLineRequest(
        @NotNull(message = "itemId is required")
        Long itemId,

        @NotNull(message = "receivedQuantity is required")
        @Positive(message = "receivedQuantity must be positive")
        Integer receivedQuantity
) {
}
