package com.shifa.oms.gst.filing.domain;

/**
 * The sign convention for a reconciliation difference, computed as the return figure minus the
 * corresponding ledger/statement figure (GST returns &amp; filing, Reqs 7.3, 7.4).
 *
 * <ul>
 *   <li>{@link #RETURN_OVER_LEDGER} — positive difference: the return figure exceeds the
 *       ledger/statement figure.</li>
 *   <li>{@link #LEDGER_OVER_RETURN} — negative difference: the ledger/statement figure exceeds the
 *       return figure.</li>
 *   <li>{@link #EQUAL} — zero difference: the two figures agree exactly.</li>
 * </ul>
 *
 * <p>Pure and Spring-free; classification is performed by {@code ReconciliationMath}.
 */
public enum DifferenceDirection {
    RETURN_OVER_LEDGER,
    LEDGER_OVER_RETURN,
    EQUAL
}
