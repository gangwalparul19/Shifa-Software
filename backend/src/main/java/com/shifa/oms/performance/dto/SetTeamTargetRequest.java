package com.shifa.oms.performance.dto;

import com.shifa.oms.performance.TargetPeriodType;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Upsert a TEAM target (keyed by the team lead) for a WEEK or MONTH period
 * (CRM expansion Phase 2, Task 13; Req 12.1).
 */
public record SetTeamTargetRequest(
        @NotNull Long teamLeadId,
        @NotNull TargetPeriodType periodType,
        @NotNull LocalDate anchor,
        @NotNull @DecimalMin("0.00") BigDecimal targetAmount,
        @DecimalMin("0.00") @DecimalMax("100.00") BigDecimal conversionTarget) {
}
