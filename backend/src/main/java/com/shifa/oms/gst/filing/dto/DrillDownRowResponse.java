package com.shifa.oms.gst.filing.dto;

import com.shifa.oms.gst.filing.ReconciliationService.DrillDownRow;

import java.math.BigDecimal;
import java.util.List;

/**
 * One contributing row behind a reconciliation figure for the API (GST returns &amp; filing,
 * Req 9.3): a business identifier (order code or voucher-line ref), its type, and its signed
 * contribution to the figure. Exposes no customer PII — only the business document identifier the
 * finance/reporting drill-downs already surface (Req 10.5). Money is scale-2.
 *
 * @param identifier         the contributing document's identifier (e.g. order code, {@code VL-<id>})
 * @param type               the contributor type ({@code "ORDER"} or {@code "VOUCHER_LINE"})
 * @param signedContribution the signed amount this row contributes to the figure, scale-2
 */
public record DrillDownRowResponse(String identifier, String type, BigDecimal signedContribution) {

    /**
     * Maps a service {@link DrillDownRow} to the API payload.
     *
     * @param row the drill-down row from {@code ReconciliationService}
     * @return the response payload
     */
    public static DrillDownRowResponse from(DrillDownRow row) {
        return new DrillDownRowResponse(row.identifier(), row.type(), row.signedContribution());
    }

    /**
     * Maps a list of service drill-down rows to API payloads, preserving order.
     *
     * @param rows the contributing rows (may be empty, never {@code null})
     * @return the response payloads
     */
    public static List<DrillDownRowResponse> fromAll(List<DrillDownRow> rows) {
        return rows.stream().map(DrillDownRowResponse::from).toList();
    }
}
