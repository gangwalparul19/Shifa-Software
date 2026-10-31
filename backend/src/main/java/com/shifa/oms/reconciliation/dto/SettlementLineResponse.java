package com.shifa.oms.reconciliation.dto;

import com.shifa.oms.reconciliation.SettlementAllocation;
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
        String message,
        boolean dateDefaulted,
        String resolutionReason,
        String resolutionTargetKey) {

    public SettlementLineResponse(Long id, int sourceRowNumber, String orderCode, String shopifyOrderId,
                                  String awb, String utr, LocalDate transactionDate, BigDecimal amount,
                                  BigDecimal expectedAmount, BigDecimal variance, BigDecimal allocatedAmount,
                                  BigDecimal suspenseAmount, SettlementMatchStatus matchStatus,
                                  SettlementMatchMethod matchMethod, Long resolvedOrderId, Long resolvedPaymentId,
                                  Long resolvedReceivableId, String message) {
        this(id, sourceRowNumber, orderCode, shopifyOrderId, awb, utr, transactionDate, amount, expectedAmount,
                variance, allocatedAmount, suspenseAmount, matchStatus, matchMethod, resolvedOrderId,
                resolvedPaymentId, resolvedReceivableId, message, false, null, null);
    }

    public SettlementLineResponse(Long id, int sourceRowNumber, String orderCode, String shopifyOrderId,
                                  String awb, String utr, LocalDate transactionDate, BigDecimal amount,
                                  BigDecimal expectedAmount, BigDecimal variance, BigDecimal allocatedAmount,
                                  BigDecimal suspenseAmount, SettlementMatchStatus matchStatus,
                                  SettlementMatchMethod matchMethod, Long resolvedOrderId, Long resolvedPaymentId,
                                  Long resolvedReceivableId, String message, boolean dateDefaulted,
                                  String resolutionReason) {
        this(id, sourceRowNumber, orderCode, shopifyOrderId, awb, utr, transactionDate, amount, expectedAmount,
                variance, allocatedAmount, suspenseAmount, matchStatus, matchMethod, resolvedOrderId,
                resolvedPaymentId, resolvedReceivableId, message, dateDefaulted, resolutionReason, null);
    }

    public static SettlementLineResponse from(SettlementLine line) {
        return from(line, null);
    }

    public static SettlementLineResponse from(SettlementLine line, SettlementAllocation allocation) {
        return new SettlementLineResponse(line.getId(), line.getSourceRowNumber(), line.getOrderCode(),
                line.getShopifyOrderId(), line.getAwb(), line.getUtr(), line.getTransactionDate(), line.getAmount(),
                line.getExpectedAmount(), line.getVariance(), line.getAllocatedAmount(), line.getSuspenseAmount(),
                line.getMatchStatus(), line.getMatchMethod(), line.getResolvedOrderId(), line.getResolvedPaymentId(),
                line.getResolvedReceivableId(), line.getMessage(), line.isDateDefaulted(),
                allocation == null ? null : allocation.getReason(),
                allocation == null ? null : allocation.getTargetKey());
    }
}
