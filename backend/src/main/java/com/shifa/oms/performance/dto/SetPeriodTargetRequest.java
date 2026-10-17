package com.shifa.oms.performance.dto;

import com.shifa.oms.performance.TargetPeriodType;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Upsert a per-salesperson WEEK or MONTH target with an optional incentive and
 * conversion goal (CRM expansion Phase 2, Task 13; Req 12.1). {@code anchor} is
 * any date within the period (normalised server-side to the period anchor).
 */
public record SetPeriodTargetRequest(
        @NotNull Long salespersonId,
        @NotNull TargetPeriodType periodType,
        @NotNull LocalDate anchor,
        @NotNull @DecimalMin("0.00") BigDecimal targetAmount,
        @DecimalMin("0.00") @DecimalMax("100.00") BigDecimal incentivePct,
        @DecimalMin("0.00") @DecimalMax("100.00") BigDecimal conversionTarget) {
}
