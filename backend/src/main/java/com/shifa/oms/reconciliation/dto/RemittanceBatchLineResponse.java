package com.shifa.oms.reconciliation.dto;

import com.shifa.oms.reconciliation.RemittanceBatchLine;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A per-order line in a committed remittance import, for the settlement report's
 * details view (backend {@link RemittanceBatchLine}).
 */
public record RemittanceBatchLineResponse(
        Long id,
        Long batchId,
        int sourceRowNumber,
        String awb,
        String clientOrderId,
        String customerName,
        Long resolvedOrderId,
        String resolvedOrderCode,
        BigDecimal expectedAmount,
        BigDecimal remittedAmount,
        BigDecimal settledAmount,
        BigDecimal shortfallAmount,
        LocalDate remittedDate,
        RemittanceRowResult.Status status,
        String message
) {
    public static RemittanceBatchLineResponse from(RemittanceBatchLine l) {
        return new RemittanceBatchLineResponse(
                l.getId(),
                l.getBatch() == null ? null : l.getBatch().getId(),
                l.getSourceRowNumber(),
                l.getAwb(),
                l.getClientOrderId(),
                l.getCustomerName(),
                l.getResolvedOrderId(),
                l.getResolvedOrderCode(),
                l.getExpectedAmount(),
                l.getRemittedAmount(),
                l.getSettledAmount(),
                l.getShortfallAmount(),
                l.getRemittedDate(),
                l.getStatus(),
                l.getMessage());
    }
}
