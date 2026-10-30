package com.shifa.oms.reconciliation.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Account-wise upfront-payment reconciliation for a selected IST date window. */
public record SettlementKpiResponse(
        LocalDate from,
        LocalDate to,
        BigDecimal omsBookedAmount,
        BigDecimal bankStatementAmount,
        BigDecimal bankMatchedAmount,
        BigDecimal bankUnmatchedAmount,
        BigDecimal difference,
        BigDecimal verifiedAmount,
        BigDecimal pendingAmount,
        BigDecimal rejectedAmount,
        BigDecimal unreviewedAmount,
        List<AccountSettlementKpi> accounts
) {

    public record AccountSettlementKpi(
            Long accountId,
            String accountName,
            BigDecimal omsBookedAmount,
            BigDecimal verifiedAmount,
            BigDecimal pendingAmount,
            BigDecimal rejectedAmount,
            BigDecimal unreviewedAmount,
            BigDecimal bankStatementAmount,
            BigDecimal bankMatchedAmount,
            BigDecimal bankUnmatchedAmount,
            BigDecimal difference,
            long statementRows,
            long matchedRows,
            long unmatchedRows
    ) {
    }
}
