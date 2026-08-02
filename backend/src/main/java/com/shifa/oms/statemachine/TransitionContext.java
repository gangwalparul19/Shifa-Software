package com.shifa.oms.statemachine;

/**
 * The facts about an order, beyond its current status, that affect who may move it
 * (spec {@code shopify-quikshipx-order-sync}, Req 9).
 *
 * <p>Passing this as an explicit argument rather than having
 * {@link TransitionAuthority} look it up keeps the authority pure and
 * property-testable, and keeps {@code statemachine} free of any dependency on
 * {@code order} — today the dependency runs the other way, and inverting it would
 * make the state machine untestable in isolation.
 *
 * <p>{@link #LEGACY} is the identity: it reports an order that no courier manages
 * and no external channel created, which is every order that existed before this
 * feature. Under {@code LEGACY} the context-aware decisions are definitionally
 * equal to the pre-existing ones — the property that protects the existing suite.
 *
 * @param channel          whether the order came from an external storefront
 * @param courierManaged   whether an external courier owns this order's fulfilment,
 *                         that is, a shipment record exists AND fallback mode is off.
 *                         Callers fold fallback mode in here so the authority has a
 *                         single boolean to reason about
 * @param fallbackMode     whether an admin has returned fulfilment authority to Shifa
 *                         for this one order; retained for diagnostics and for callers
 *                         that need to distinguish "never managed" from "taken back"
 */
public record TransitionContext(ChannelView channel, boolean courierManaged, boolean fallbackMode) {

    /** How the order entered the system, as far as the state machine needs to know. */
    public enum ChannelView {
        /** Placed on an external storefront and ingested, so it arrives already committed. */
        EXTERNAL_STOREFRONT,
        /** Everything else, including every order punched in the Shifa Admin Portal. */
        INTERNAL
    }

    /**
     * The pre-feature context: nothing is courier-managed and nothing came from an
     * external storefront. Used by every existing call site and by the legacy
     * overloads, so their behaviour is provably unchanged.
     */
    public static final TransitionContext LEGACY =
            new TransitionContext(ChannelView.INTERNAL, false, false);

    public TransitionContext {
        if (channel == null) {
            channel = ChannelView.INTERNAL;
        }
        // An order in fallback mode is by definition not courier-managed: an admin has
        // taken it back. Normalising here means no caller can construct a contradictory
        // context, so the authority never has to resolve the conflict itself.
        if (fallbackMode) {
            courierManaged = false;
        }
    }

    /** Whether this order was placed on an external storefront. */
    public boolean isExternalStorefront() {
        return channel == ChannelView.EXTERNAL_STOREFRONT;
    }
}
