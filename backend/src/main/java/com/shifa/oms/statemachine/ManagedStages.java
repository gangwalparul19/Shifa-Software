package com.shifa.oms.statemachine;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * The lifecycle stages an external courier owns once it has taken the shipment
 * (spec {@code shopify-quikshipx-order-sync}, glossary "Managed_Stage").
 *
 * <p>Everything at or after {@code LABEL_GENERATED}: from the moment the courier
 * prints the label, it — not Shifa — knows where the parcel is. The stages before
 * that ({@code PENDING_ADMIN_APPROVAL}, {@code APPROVED}) stay Shifa's, because
 * approval is an internal commercial decision, as are {@code REJECTED} and
 * {@code CANCELLED}, which are refusals to ship at all.
 *
 * <p>Pure and immutable, so the transition authority can consult it without a
 * database round trip on every decision.
 */
public final class ManagedStages {

    private static final Set<OrderStatus> STAGES = Collections.unmodifiableSet(EnumSet.of(
            OrderStatus.LABEL_GENERATED,
            OrderStatus.PACKED,
            OrderStatus.HANDED_TO_DELIVERY,
            OrderStatus.COURIER_ASSIGNED,
            OrderStatus.DISPATCHED,
            OrderStatus.IN_TRANSIT,
            OrderStatus.OUT_FOR_DELIVERY,
            OrderStatus.DELIVERED,
            OrderStatus.CUSTOMER_REJECTED,
            OrderStatus.DELIVERY_FAILED,
            OrderStatus.COD_COLLECTED,
            OrderStatus.CLOSED,
            OrderStatus.RTO,
            OrderStatus.REDISPATCH));

    private ManagedStages() {
        // Pure static helper.
    }

    /** Whether the courier owns this stage for a courier-managed order. */
    public static boolean contains(OrderStatus status) {
        return status != null && STAGES.contains(status);
    }

    /** The managed stages, as an unmodifiable set. */
    public static Set<OrderStatus> all() {
        return STAGES;
    }
}
