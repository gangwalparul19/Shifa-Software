package com.shifa.oms.order.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/**
 * Salesperson order-entry payload for {@code POST /api/orders} (Requirement 7).
 *
 * <p>Requires customer name, a 10-digit mobile, and a complete shipping address
 * (Req 7.1), at least one line item (Req 7.2), and an {@code amountReceived}
 * (Req 7.5). {@code paymentScreenshotKey} references a previously uploaded
 * screenshot (two-step upload via {@code POST /api/orders/payment-screenshots})
 * and is mandatory when {@code amountReceived > 0} (Req 7.6) — that rule is
 * enforced in the service so the specific message can be returned.
 */
public record CreateOrderRequest(
        @NotBlank(message = "customerName is required")
        @Size(max = 100, message = "customerName must be at most 100 characters")
        String customerName,

        @NotBlank(message = "customerMobile is required")
        @Pattern(regexp = "\\d{10}", message = "customerMobile must be exactly 10 digits")
        String customerMobile,

        @NotBlank(message = "addressLine is required")
        @Size(max = 250, message = "addressLine must be at most 250 characters")
        String addressLine,

        @NotBlank(message = "city is required")
        @Size(max = 100, message = "city must be at most 100 characters")
        String city,

        @NotBlank(message = "state is required")
        @Size(max = 100, message = "state must be at most 100 characters")
        String state,

        @NotBlank(message = "postalCode is required")
        @Pattern(regexp = "\\d{6}", message = "postalCode must be exactly 6 digits")
        String postalCode,

        @NotEmpty(message = "at least one line item is required")
        @Valid
        List<LineItemRequest> items,

        @NotNull(message = "amountReceived is required")
        @DecimalMin(value = "0.00", message = "amountReceived must not be negative")
        @Digits(integer = 10, fraction = 2, message = "amountReceived must be a DECIMAL(12,2) value")
        BigDecimal amountReceived,

        String paymentScreenshotKey
) {
}
