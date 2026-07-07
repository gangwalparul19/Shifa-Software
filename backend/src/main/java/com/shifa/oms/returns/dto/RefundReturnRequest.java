package com.shifa.oms.returns.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * Mark-refunded payload for a return ({@code POST /api/admin/returns/{id}/refund}).
 *
 * @param refundAmount the refund amount recorded when marking refunded (required)
 */
public record RefundReturnRequest(
        @NotNull(message = "refundAmount is required")
        @DecimalMin(value = "0.00", message = "refundAmount must not be negative")
        @Digits(integer = 10, fraction = 2, message = "refundAmount must be a DECIMAL(12,2) value")
        BigDecimal refundAmount
) {
}
