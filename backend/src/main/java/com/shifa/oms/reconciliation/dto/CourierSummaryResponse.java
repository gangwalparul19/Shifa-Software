package com.shifa.oms.reconciliation.dto;

import java.math.BigDecimal;

/**
 * Per-courier outstanding-receivable summary for the reconciliation dashboard
 * (Req 18.1, 18.2). Totals cover only <em>unsettled</em> receivables, so
 * settling a receivable reduces the courier's outstanding by exactly that
 * amount (Req 18.5). RTO orders never create a COD receivable, so they are
 * naturally excluded from the COD total (Req 18.6).
 *
 * @param courierCompanyId  the courier company
 * @param courierName       the courier company display name, or {@code null}
 * @param codOutstanding    unsettled COD receivable total (Req 18.1)
 * @param claimOutstanding  unsettled claim receivable total (Req 18.2)
 * @param totalOutstanding  the sum of COD + claim outstanding
 */
public record CourierSummaryResponse(
        Long courierCompanyId,
        String courierName,
        BigDecimal codOutstanding,
        BigDecimal claimOutstanding,
        BigDecimal totalOutstanding) {
}
