package com.shifa.oms.order.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * A single line in a salesperson order-entry request (Req 7.2, 7.3).
 *
 * <p>{@code productId} and {@code quantity} are required. {@code rate} is
 * optional: when omitted the product's default sale price is pre-filled
 * (Req 7.2); when supplied it overrides the default for that line (Req 7.3).
 */
public record LineItemRequest(
        @NotNull(message = "productId is required")
        Long productId,

        @Min(value = 1, message = "quantity must be at least 1")
        @Max(value = 999, message = "quantity must be at most 999")
        int quantity,

        @DecimalMin(value = "0.00", message = "rate must not be negative")
        @Digits(integer = 10, fraction = 2, message = "rate must be a DECIMAL(12,2) value")
        BigDecimal rate
) {
}
