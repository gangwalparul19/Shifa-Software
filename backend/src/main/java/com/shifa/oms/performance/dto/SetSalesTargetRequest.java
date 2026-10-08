package com.shifa.oms.performance.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;

/**
 * Upsert a salesperson's monthly target ({@code PUT /api/admin/salespeople/targets},
 * FEATURE-ROADMAP §6.1).
 *
 * @param salespersonId the salesperson (required)
 * @param month         target month {@code yyyy-MM} (required)
 * @param targetAmount  the monthly revenue target (required, ≥ 0)
 * @param incentivePct  optional incentive rate (0–100) paid on achievement when the target is met
 */
public record SetSalesTargetRequest(
        @NotNull(message = "salespersonId is required")
        Long salespersonId,

        @NotNull(message = "month is required")
        @Pattern(regexp = "\\d{4}-\\d{2}", message = "month must be in yyyy-MM format")
        String month,

        @NotNull(message = "targetAmount is required")
        @DecimalMin(value = "0.00", message = "targetAmount must not be negative")
        BigDecimal targetAmount,

        @DecimalMin(value = "0.00", message = "incentivePct must not be negative")
        @DecimalMax(value = "100.00", message = "incentivePct must be at most 100")
        BigDecimal incentivePct
) {
}
