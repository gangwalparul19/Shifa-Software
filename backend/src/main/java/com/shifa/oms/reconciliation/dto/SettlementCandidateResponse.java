package com.shifa.oms.reconciliation.dto;

import com.shifa.oms.reconciliation.SettlementTargetType;
import java.math.BigDecimal;

/** Safe target metadata for resolving one settlement line; never exposes storage keys or full account numbers. */
public record SettlementCandidateResponse(SettlementTargetType targetType, Long targetId, Long orderId,
                                          String orderCode, String customerName, BigDecimal expectedAmount,
                                          BigDecimal allocatedAmount, BigDecimal availableAmount,
                                          String reference) { }
