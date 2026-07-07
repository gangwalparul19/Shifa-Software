package com.shifa.oms.returns.dto;

import com.shifa.oms.returns.OrderReturn;
import com.shifa.oms.returns.ReturnStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Read projection for a single return record ({@code GET /api/admin/returns},
 * "operations depth" Feature 1). Flat and stable for the admin table.
 */
public record ReturnResponse(
        Long id,
        Long orderId,
        String reason,
        String notes,
        ReturnStatus status,
        BigDecimal refundAmount,
        boolean restocked,
        Long createdBy,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {

    public static ReturnResponse from(OrderReturn r) {
        return new ReturnResponse(
                r.getId(),
                r.getOrderId(),
                r.getReason(),
                r.getNotes(),
                r.getStatus(),
                r.getRefundAmount(),
                r.isRestocked(),
                r.getCreatedBy(),
                r.getCreatedAt(),
                r.getUpdatedAt());
    }
}
