package com.shifa.oms.reconciliation.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A delivered COD order whose {@code COD_RECEIVABLE} has not yet been settled
 * (Req 18.3). Backs the "unsettled COD" list and the per-row settle action.
 *
 * @param receivableId     the receivable id to settle (Req 18.5)
 * @param orderId          the delivered COD order
 * @param orderCode        the order's human/barcode code
 * @param customerName     the customer name (for display)
 * @param courierCompanyId the courier that collected the cash
 * @param courierName      the courier display name, or {@code null}
 * @param awb              the shipment AWB, or {@code null}
 * @param amount           the COD amount owed by the courier
 * @param createdAt        when the receivable was recorded
 */
public record UnsettledCodResponse(
        Long receivableId,
        Long orderId,
        String orderCode,
        String customerName,
        Long courierCompanyId,
        String courierName,
        String awb,
        BigDecimal amount,
        LocalDateTime createdAt) {
}
