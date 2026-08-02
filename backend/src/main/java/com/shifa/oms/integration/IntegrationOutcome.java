package com.shifa.oms.integration;

import java.util.EnumSet;
import java.util.Set;

/**
 * The processing verdict recorded against an {@link IntegrationEvent}
 * (spec {@code shopify-quikshipx-order-sync}, Requirements 2, 5, 6, 7, 8, 13, 14).
 *
 * <p>Exactly one outcome is recorded per event, and only {@link #APPLIED} and
 * {@link #PROCESSED} change an order. Everything else is a deliberate no-op with a
 * reason, which is what makes the admin health console meaningful rather than a
 * wall of unexplained failures.
 *
 * <p>{@link #unresolvedFailures()} is the single definition of "needs a human",
 * shared by the health console list and the dashboard count so the two can never
 * disagree (Req 14.2, 14.7).
 */
public enum IntegrationOutcome {

    // --- In flight ---------------------------------------------------------

    /** Delivery accepted and stored; asynchronous processing not yet finished. */
    RECEIVED,

    // --- Success ----------------------------------------------------------

    /** A Shopify order was ingested, or a publication succeeded. */
    PROCESSED,

    /** A QuikShipX status event produced one or more status transitions (Req 7.5). */
    APPLIED,

    /** The mapped target status already equalled the current status (Req 7.8). */
    NO_CHANGE,

    // --- Benign no-ops (visible in the console, no alert) ------------------

    /** A repeat delivery of an event already in the store (Req 2.6, 6.11). */
    DUPLICATE,

    /** The event's status timestamp was not newer than the last synced one (Req 6.7). */
    SUPERSEDED,

    /** The order is in Fallback_Mode, so QuikShipX does not drive it (Req 13.6). */
    FALLBACK_SUPPRESSED,

    // --- Failures (surface in the health console) -------------------------

    /** A required field was absent, unparseable or out of range (Req 8.7, 8.9, 8.10). */
    MALFORMED_PAYLOAD,

    /** No Shipment_Record matched the referenced shipment (Req 6.6). */
    UNKNOWN_SHIPMENT,

    /** The status token is not in the configured mapping (Req 7.4). */
    UNMAPPED_STATUS,

    /** No legal transition path reaches the mapped target status (Req 7.7). */
    ILLEGAL_TRANSITION,

    /** A transition on a computed path was rejected, so the event rolled back (Req 7.9). */
    TRANSITION_FAILED,

    /** Asynchronous processing failed after every retry (Req 2.10, 6.13). */
    PROCESSING_FAILED,

    /** A QuikShipX submission failed after every retry, or was permanently rejected (Req 5.7, 5.11). */
    PUBLICATION_FAILED;

    private static final Set<IntegrationOutcome> UNRESOLVED_FAILURES = EnumSet.of(
            MALFORMED_PAYLOAD,
            UNKNOWN_SHIPMENT,
            UNMAPPED_STATUS,
            ILLEGAL_TRANSITION,
            TRANSITION_FAILED,
            PROCESSING_FAILED,
            PUBLICATION_FAILED);

    private static final Set<IntegrationOutcome> SUCCESSES = EnumSet.of(PROCESSED, APPLIED, NO_CHANGE);

    /**
     * The outcomes that represent an unresolved integration failure needing an
     * admin's attention (Req 14.2). Benign no-ops are excluded on purpose: a
     * superseded or duplicate event is correct behaviour, not a problem.
     */
    public static Set<IntegrationOutcome> unresolvedFailures() {
        return EnumSet.copyOf(UNRESOLVED_FAILURES);
    }

    /** Whether this outcome means the event was handled successfully. */
    public boolean isSuccess() {
        return SUCCESSES.contains(this);
    }

    /** Whether this outcome should surface in the admin health console. */
    public boolean isFailure() {
        return UNRESOLVED_FAILURES.contains(this);
    }

    /**
     * Whether retrying could plausibly help. A malformed payload or an unmapped
     * status will fail identically on every attempt, so those are recorded once
     * and never retried (Req 6.6, 8.7).
     */
    public boolean isRetryable() {
        return this == PROCESSING_FAILED || this == PUBLICATION_FAILED || this == TRANSITION_FAILED;
    }
}
