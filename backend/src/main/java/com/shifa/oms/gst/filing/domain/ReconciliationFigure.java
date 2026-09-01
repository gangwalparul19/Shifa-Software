package com.shifa.oms.gst.filing.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * A single reconciled figure in a reconciliation summary: one return figure compared against its
 * corresponding General Ledger / Financial Statement figure for a return period
 * (GST returns &amp; filing, Reqs 7.1–7.5, 8.1–8.4, 9.1).
 *
 * <p>It bundles the two compared values together with the derived {@link #difference} (return minus
 * ledger, scale-2 {@code HALF_UP}), its {@link #direction}, and whether the pair is
 * {@link #reconciled} within the configured tolerance. Instances are normally produced by
 * {@link ReconciliationMath#figure(String, BigDecimal, BigDecimal, BigDecimal)} so that the derived
 * fields are always consistent with the arithmetic.
 *
 * <p>Pure and Spring-free; no JPA. All monetary components are normalised to scale-2.
 *
 * @param label       a human-readable name for the compared figure (e.g. "GSTR-1 total output tax")
 * @param returnValue the figure the returns system presents, scale-2
 * @param ledgerValue the corresponding ledger/statement figure, scale-2
 * @param difference  {@code returnValue − ledgerValue}, scale-2 {@code HALF_UP}
 * @param direction   the sign of {@code difference} (see {@link DifferenceDirection})
 * @param reconciled  whether {@code |difference| ≤ tolerance}
 */
public record ReconciliationFigure(
        String label,
        BigDecimal returnValue,
        BigDecimal ledgerValue,
        BigDecimal difference,
        DifferenceDirection direction,
        boolean reconciled) {

    /** Normalises the monetary components to scale-2 and validates required fields. */
    public ReconciliationFigure {
        Objects.requireNonNull(returnValue, "returnValue");
        Objects.requireNonNull(ledgerValue, "ledgerValue");
        Objects.requireNonNull(difference, "difference");
        Objects.requireNonNull(direction, "direction");
        returnValue = returnValue.setScale(2, RoundingMode.HALF_UP);
        ledgerValue = ledgerValue.setScale(2, RoundingMode.HALF_UP);
        difference = difference.setScale(2, RoundingMode.HALF_UP);
    }
}
