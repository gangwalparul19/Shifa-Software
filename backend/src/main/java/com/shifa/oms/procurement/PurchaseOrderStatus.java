package com.shifa.oms.procurement;

/**
 * The lifecycle status of a {@link PurchaseOrder} (Feature C2).
 *
 * <p>The happy path is {@link #ORDERED} &rarr; {@link #RECEIVED}, with
 * {@link #PARTIALLY_RECEIVED} as an intermediate state when only some of the
 * ordered quantity has been received so far. {@link #DRAFT} is modelled for
 * completeness (a not-yet-placed order) and {@link #CANCELLED} is a terminal
 * state reachable only from a not-yet-received PO.
 *
 * <p>This API creates POs directly in {@link #ORDERED} (there is no separate
 * "submit draft" step), so a freshly created PO is immediately receivable or
 * cancellable.
 */
public enum PurchaseOrderStatus {

    /** Saved but not yet placed with the supplier (reserved; not used by create). */
    DRAFT,

    /** Placed with the supplier, awaiting goods. Receivable and cancellable. */
    ORDERED,

    /** Some — but not all — ordered quantity has been received. Still receivable. */
    PARTIALLY_RECEIVED,

    /** All ordered quantity received (terminal). */
    RECEIVED,

    /** Cancelled before receipt (terminal). */
    CANCELLED;

    /** Whether goods may still be received against a PO in this status. */
    public boolean isReceivable() {
        return this == ORDERED || this == PARTIALLY_RECEIVED;
    }

    /** Whether a PO in this status may be cancelled (only before any receipt). */
    public boolean isCancellable() {
        return this == DRAFT || this == ORDERED;
    }

    /** Whether this is a terminal status (no further changes allowed). */
    public boolean isTerminal() {
        return this == RECEIVED || this == CANCELLED;
    }
}
