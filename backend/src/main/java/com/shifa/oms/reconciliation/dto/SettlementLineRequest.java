package com.shifa.oms.reconciliation.dto;

import com.shifa.oms.reconciliation.SettlementTargetType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/** Explicit operator resolution of one imported suspense line. */
public record SettlementLineRequest(
        @NotNull SettlementTargetType targetType,
        Long orderId,
        Long paymentId,
        Long receivableId,
        @NotNull @DecimalMin("0.01") BigDecimal amount) {
}
