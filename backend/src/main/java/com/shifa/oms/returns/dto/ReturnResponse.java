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
        // The order's human-readable code (e.g. SHR-20260916-JGM9), so the admin UI
        // never has to display or link by the raw numeric id. Null only if the
        // order has since been deleted (should not happen in practice).
        String orderCode,
        String reason,
        String notes,
        ReturnStatus status,
        // The CASH refunded to the customer (money out) — for an RTO only what was
        // actually collected, so zero for a pure COD order.
        BigDecimal refundAmount,
        // The GST-inclusive VALUE OF SUPPLY reversed by the credit note (V64), which
        // for a whole-consignment return/RTO is the full invoice value regardless of
        // how much cash was collected. This is what GSTR-1 CDNR/CDNUR reports. Null
        // on legacy rows written before the two amounts were separated.
        BigDecimal creditNoteValue,
        boolean restocked,
        Long createdBy,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {

    /** Builds a response without a resolved order code (rare fallback path). */
    public static ReturnResponse from(OrderReturn r) {
        return from(r, null);
    }

    /** Builds a response with the order code resolved by the caller (batch-friendly). */
    public static ReturnResponse from(OrderReturn r, String orderCode) {
        return new ReturnResponse(
                r.getId(),
                r.getOrderId(),
                orderCode,
                r.getReason(),
                r.getNotes(),
                r.getStatus(),
                r.getRefundAmount(),
                r.getCreditNoteValue(),
                r.isRestocked(),
                r.getCreatedBy(),
                r.getCreatedAt(),
                r.getUpdatedAt());
    }
}
