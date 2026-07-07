package com.shifa.oms.returns.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;

import java.math.BigDecimal;

/**
 * Approve payload for a return ({@code POST /api/admin/returns/{id}/approve}).
 *
 * @param restock      when true the order's line items are returned to stock
 * @param refundAmount optional refund amount to record at approval time
 */
public record ApproveReturnRequest(
        boolean restock,

        @DecimalMin(value = "0.00", message = "refundAmount must not be negative")
        @Digits(integer = 10, fraction = 2, message = "refundAmount must be a DECIMAL(12,2) value")
        BigDecimal refundAmount
) {
}
