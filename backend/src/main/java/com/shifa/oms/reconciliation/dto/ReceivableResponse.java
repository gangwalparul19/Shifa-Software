package com.shifa.oms.reconciliation.dto;

import com.shifa.oms.reconciliation.domain.ReceivableType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A single receivable row for the reconciliation dashboard
 * (Req 18.1&ndash;18.5). Carries the persisted ledger row plus the resolved
 * order code, courier company name, and AWB so the UI can present a
 * human-readable line without extra lookups.
 *
 * @param id               the receivable id (used to settle it, Req 18.5)
 * @param orderId          the order the receivable stems from
 * @param orderCode        the order's human/barcode code
 * @param courierCompanyId the courier company that owes the amount
 * @param courierName      the courier company display name, or {@code null}
 * @param awb              the shipment AWB, or {@code null} if none assigned
 * @param type             COD or claim receivable
 * @param amount           the amount owed
 * @param settled          whether the receivable has been settled (Req 18.5)
 * @param settledDate      the settlement date, or {@code null} when outstanding
 * @param createdAt        when the receivable was recorded
 */
public record ReceivableResponse(
        Long id,
        Long orderId,
        String orderCode,
        Long courierCompanyId,
        String courierName,
        String awb,
        ReceivableType type,
        BigDecimal amount,
        boolean settled,
        LocalDate settledDate,
        LocalDateTime createdAt) {
}
