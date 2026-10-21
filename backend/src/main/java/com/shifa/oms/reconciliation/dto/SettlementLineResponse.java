package com.shifa.oms.reconciliation.dto;

import com.shifa.oms.reconciliation.SettlementLine;
import com.shifa.oms.reconciliation.SettlementMatchMethod;
import com.shifa.oms.reconciliation.SettlementMatchStatus;

import java.math.BigDecimal;
import java.time.LocalDate;

public record SettlementLineResponse(
        Long id,
        int sourceRowNumber,
        String orderCode,
        String shopifyOrderId,
        String awb,
        String utr,
        LocalDate transactionDate,
        BigDecimal amount,
        BigDecimal expectedAmount,
        BigDecimal variance,
        BigDecimal allocatedAmount,
        BigDecimal suspenseAmount,
        SettlementMatchStatus matchStatus,
        SettlementMatchMethod matchMethod,
        Long resolvedOrderId,
        Long resolvedPaymentId,
        Long resolvedReceivableId,
        String message) {

    public static SettlementLineResponse from(SettlementLine line) {
        return new SettlementLineResponse(line.getId(), line.getSourceRowNumber(), line.getOrderCode(),
                line.getShopifyOrderId(), line.getAwb(), line.getUtr(), line.getTransactionDate(), line.getAmount(),
                line.getExpectedAmount(), line.getVariance(), line.getAllocatedAmount(), line.getSuspenseAmount(),
                line.getMatchStatus(), line.getMatchMethod(), line.getResolvedOrderId(), line.getResolvedPaymentId(),
                line.getResolvedReceivableId(), line.getMessage());
    }
}
