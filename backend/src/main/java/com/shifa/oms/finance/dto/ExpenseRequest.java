package com.shifa.oms.finance.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Create payload for an expense ({@code POST /api/admin/expenses}).
 *
 * @param category    the expense category (required, e.g. RENT, SALARIES)
 * @param description optional free-text description
 * @param amount      the expense amount (required, non-negative money)
 * @param incurredOn  the date the expense was incurred (required, ISO yyyy-MM-dd)
 */
public record ExpenseRequest(
        @NotBlank(message = "category is required")
        @Size(max = 100, message = "category must be at most 100 characters")
        String category,

        @Size(max = 500, message = "description must be at most 500 characters")
        String description,

        @NotNull(message = "amount is required")
        @DecimalMin(value = "0.00", message = "amount must not be negative")
        @Digits(integer = 10, fraction = 2, message = "amount must be a money amount")
        BigDecimal amount,

        @NotNull(message = "incurredOn is required")
        LocalDate incurredOn
) {
}
