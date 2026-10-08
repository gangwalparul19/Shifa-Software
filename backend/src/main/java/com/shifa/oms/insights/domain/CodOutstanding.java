package com.shifa.oms.insights.domain;

import java.math.BigDecimal;

/**
 * The total unsettled COD receivable amount (design &sect;Pure domain; Req 7.2),
 * the input to COD-outstanding build-up detection.
 *
 * @param unsettledTotal the sum of unsettled COD receivables
 */
public record CodOutstanding(BigDecimal unsettledTotal) {
}
