package com.shifa.oms.gst.filing.dto;

import com.shifa.oms.gst.filing.ReconciliationService.ComparedFigure;
import com.shifa.oms.gst.filing.domain.DifferenceDirection;
import com.shifa.oms.gst.filing.domain.ReconciliationFigure;

import java.math.BigDecimal;

/**
 * One compared figure in a reconciliation summary for the API (GST returns &amp; filing,
 * Reqs 7.1, 7.3–7.5, 8.1–8.5, 9.1): the return figure against its corresponding General Ledger /
 * Financial Statement figure, the signed {@link #difference}, its {@link #direction}, and whether
 * the pair is {@link #reconciled} within tolerance. Mirrors the pure {@link ReconciliationFigure}
 * plus the stable {@link #key} and the {@link #comparisonAvailable} flag (Req 8.5). Money is
 * scale-2; enums serialise as {@code name()}.
 *
 * @param key                 the stable figure key (e.g. {@code GSTR1_OUTPUT_TAX})
 * @param label               a human-readable name for the figure
 * @param returnValue         the figure the returns system presents, scale-2
 * @param ledgerValue         the corresponding ledger/statement figure, scale-2
 * @param difference          {@code returnValue − ledgerValue}, scale-2
 * @param direction           the sign of the difference
 * @param reconciled          whether {@code |difference| ≤ tolerance}
 * @param comparisonAvailable whether the ledger/statement source was available; when {@code false}
 *                            the ledger value and difference are not meaningful (Req 8.5)
 */
public record ReconciliationFigureResponse(
        String key,
        String label,
        BigDecimal returnValue,
        BigDecimal ledgerValue,
        BigDecimal difference,
        DifferenceDirection direction,
        boolean reconciled,
        boolean comparisonAvailable) {

    /**
     * Maps a service {@link ComparedFigure} (pure arithmetic + key + availability) to the API payload.
     *
     * @param compared the compared figure from {@code ReconciliationService}
     * @return the response payload
     */
    public static ReconciliationFigureResponse from(ComparedFigure compared) {
        ReconciliationFigure figure = compared.figure();
        return new ReconciliationFigureResponse(
                compared.key(),
                figure.label(),
                figure.returnValue(),
                figure.ledgerValue(),
                figure.difference(),
                figure.direction(),
                figure.reconciled(),
                compared.comparisonAvailable());
    }
}
