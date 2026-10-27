package com.shifa.oms.reconciliation.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A partially-settled COD order to follow up next cycle (V99): the courier's
 * remittance was short of the expected COD, so part was settled and a shortfall
 * stays outstanding. Powers the Settlement Report's "Partial — follow up" list.
 *
 * @param receivableId   the COD receivable id
 * @param orderId        the order id
 * @param orderCode      the order code (deep link)
 * @param customerName   the customer name
 * @param awb            the courier AWB / tracking id, when known
 * @param expectedAmount the full COD amount expected
 * @param settledAmount  the amount actually remitted + settled so far
 * @param shortfall      the amount still outstanding ({@code expected - settled})
 * @param settledDate    the date the partial settlement was recorded
 */
public record PartialSettlementResponse(
        Long receivableId,
        Long orderId,
        String orderCode,
        String customerName,
        String awb,
        BigDecimal expectedAmount,
        BigDecimal settledAmount,
        BigDecimal shortfall,
        LocalDate settledDate
) {
}
