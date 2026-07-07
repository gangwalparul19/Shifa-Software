package com.shifa.oms.reconciliation.domain;

/**
 * The kind of amount owed to the business that is tracked in the receivables
 * ledger (Requirement 16.2, 17.2; design table {@code receivables}).
 *
 * <ul>
 *   <li>{@link #COD_RECEIVABLE} — cash the courier collected on delivery of a
 *       COD order and now owes back to the business (Req 16.2).</li>
 *   <li>{@link #CLAIM_RECEIVABLE} — the net order amount recoverable from the
 *       courier for a lost or damaged shipment (Req 17.2).</li>
 * </ul>
 */
public enum ReceivableType {
    COD_RECEIVABLE,
    CLAIM_RECEIVABLE
}
