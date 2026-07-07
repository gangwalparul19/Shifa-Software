package com.shifa.oms.finance.dto;

import com.shifa.oms.finance.Expense;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Read projection for a single expense ({@code /api/admin/expenses}).
 */
public record ExpenseResponse(
        Long id,
        String category,
        String description,
        BigDecimal amount,
        LocalDate incurredOn,
        Long createdBy,
        LocalDateTime createdAt
) {

    public static ExpenseResponse from(Expense e) {
        return new ExpenseResponse(
                e.getId(),
                e.getCategory(),
                e.getDescription(),
                e.getAmount(),
                e.getIncurredOn(),
                e.getCreatedBy(),
                e.getCreatedAt());
    }
}
