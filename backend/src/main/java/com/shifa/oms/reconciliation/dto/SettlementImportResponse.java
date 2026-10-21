package com.shifa.oms.reconciliation.dto;

import com.shifa.oms.reconciliation.SettlementMatchStatus;
import com.shifa.oms.reconciliation.SettlementSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Dry-run or committed settlement import result. */
public record SettlementImportResponse(
        boolean dryRun,
        Long batchId,
        SettlementSource sourceChannel,
        String externalReference,
        LocalDate statementDate,
        int totalRows,
        BigDecimal totalAmount,
        BigDecimal allocatedAmount,
        BigDecimal suspenseAmount,
        Map<SettlementMatchStatus, Integer> counts,
        List<SettlementLineResponse> lines) {
}
