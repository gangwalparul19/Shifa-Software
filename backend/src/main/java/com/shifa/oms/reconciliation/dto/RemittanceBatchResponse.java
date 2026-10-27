package com.shifa.oms.reconciliation.dto;

import com.shifa.oms.reconciliation.RemittanceBatch;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A committed remittance import summary row for the weekly settlement report
 * (backend {@link RemittanceBatch}).
 */
public record RemittanceBatchResponse(
        Long id,
        String originalFilename,
        String externalReference,
        LocalDate remittedDate,
        LocalDateTime importedAt,
        int totalRows,
        int settledCount,
        int partialCount,
        int mismatchCount,
        int alreadySettledCount,
        int notFoundCount,
        int noReceivableCount,
        int errorCount,
        BigDecimal totalRemitted,
        BigDecimal totalSettled,
        BigDecimal totalShortfall
) {
    public static RemittanceBatchResponse from(RemittanceBatch b) {
        return new RemittanceBatchResponse(
                b.getId(),
                b.getOriginalFilename(),
                b.getExternalReference(),
                b.getRemittedDate(),
                b.getCreatedAt(),
                b.getTotalRows(),
                b.getSettledCount(),
                b.getPartialCount(),
                b.getMismatchCount(),
                b.getAlreadySettledCount(),
                b.getNotFoundCount(),
                b.getNoReceivableCount(),
                b.getErrorCount(),
                b.getTotalRemitted(),
                b.getTotalSettled(),
                b.getTotalShortfall());
    }
}
