package com.shifa.oms.reconciliation.dto;

import com.shifa.oms.reconciliation.SettlementBatch;
import com.shifa.oms.reconciliation.SettlementBatchStatus;
import com.shifa.oms.reconciliation.SettlementSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public record SettlementBatchResponse(
        Long id,
        SettlementSource sourceChannel,
        String externalReference,
        LocalDate statementDate,
        Long destinationAccountId,
        String destinationAccountName,
        String originalFilename,
        SettlementBatchStatus status,
        int lineCount,
        BigDecimal totalAmount,
        BigDecimal allocatedAmount,
        BigDecimal suspenseAmount,
        LocalDateTime createdAt,
        LocalDateTime postedAt) {

    public static SettlementBatchResponse from(SettlementBatch batch) {
        return new SettlementBatchResponse(batch.getId(), batch.getSourceChannel(), batch.getExternalReference(),
                batch.getStatementDate(), batch.getDestinationAccount() == null ? null : batch.getDestinationAccount().getId(),
                batch.getDestinationAccount() == null ? null : batch.getDestinationAccount().getDisplayName(),
                batch.getOriginalFilename(), batch.getStatus(), batch.getLineCount(), batch.getTotalAmount(),
                batch.getAllocatedAmount(), batch.getSuspenseAmount(), batch.getCreatedAt(), batch.getPostedAt());
    }
}
