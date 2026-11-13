package com.shifa.oms.order.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/** ADMIN-only replacement of the current upfront payment snapshot. */
public record CorrectPaymentRequest(
        @NotNull(message = "amountReceived is required")
        @DecimalMin(value = "0.00", message = "amountReceived must not be negative")
        @Digits(integer = 10, fraction = 2, message = "amountReceived must be a DECIMAL(12,2) value")
        BigDecimal amountReceived,

        @NotBlank(message = "paymentMethod is required")
        @Size(max = 30, message = "paymentMethod must be at most 30 characters")
        String paymentMethod,

        /** Receiving account id; required by the selected method when money was received. */
        Long paymentAccountId,

        @Size(max = 80, message = "paymentUtr must be at most 80 characters")
        String paymentUtr,

        @NotBlank(message = "reason is required")
        @Size(max = 500, message = "reason must be at most 500 characters")
        String reason) {
}
