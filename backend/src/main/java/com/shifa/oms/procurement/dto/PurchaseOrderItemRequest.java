package com.shifa.oms.procurement.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/**
 * A single line of a create-PO request: which product, how many, at what unit
 * cost.
 */
public record PurchaseOrderItemRequest(
        @NotNull(message = "productId is required")
        Long productId,

        @NotNull(message = "quantity is required")
        @Positive(message = "quantity must be positive")
        Integer quantity,

        @NotNull(message = "unitCost is required")
        @DecimalMin(value = "0.00", message = "unitCost must not be negative")
        @Digits(integer = 10, fraction = 2, message = "unitCost must be a money amount")
        BigDecimal unitCost
) {
}
