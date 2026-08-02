package com.shifa.oms.statemachine;

import com.shifa.oms.auth.Role;

import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Pure per-transition role authority for the order lifecycle (design
 * &sect;4.1/&sect;4.2). While {@link OrderStatus} is the authority on transition
 * <em>legality</em>, this component holds the orthogonal
 * {@code (from, to) -> Set<Role> (+ SYSTEM)} map that says <em>who</em> may
 * trigger each legal transition.
 *
 * <p>Two clean failure modes result: a role that is not permitted for an
 * otherwise legal transition yields {@link UnauthorizedTransitionException}
 * (HTTP 403, Req 1.5, 2.7, 12.5), whereas an illegal transition (absent from the
 * table) yields {@link IllegalStatusTransitionException} (HTTP 409) at the state
 * machine layer.
 *
 * <p>{@code SYSTEM} models the automatic actor behind courier-driven edges
 * (webhook / poller / assignment drainer, Req 15.5). It is a synthetic actor,
 * not a {@link Role}: courier progressions are authorized for {@code SYSTEM}
 * only, and are never permitted to any staff role. The
 * {@code Handed_To_Delivery -> Courier_Assigned} edge is shared — a packer/admin
 * dispatches and the system completes the assignment — so it is permitted to
 * {@code PACKING_USER}, {@code ADMIN}, and {@code SYSTEM}.
 *
 * <p>This class is pure and immutable with no Spring/persistence dependencies,
 * so it can be exercised in-memory by property-based tests.
 */
public final class TransitionAuthority {

    /**
     * The set of actors permitted to trigger a single legal transition: any
     * subset of the staff {@link Role}s plus an optional {@code SYSTEM} flag for
     * automatic (courier-driven) transitions.
     */
    private record Authorization(Set<Role> roles, boolean system) {
        Authorization {
            roles = roles.isEmpty() ? Collections.emptySet()
                    : Collections.unmodifiableSet(EnumSet.copyOf(roles));
        }

        boolean permitsRole(Role role) {
            return role != null && roles.contains(role);
        }
    }

    /** Edge key {@code (from, to)} into the authority table. */
    private record Edge(OrderStatus from, OrderStatus to) {
        Edge {
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
        }
    }

    private final Map<Edge, Authorization> table = buildTable();

    private static Map<Edge, Authorization> buildTable() {
        Map<Edge, Authorization> t = new HashMap<>();

        // Admin approval outcomes (Req 6.2, 6.3).
        put(t, OrderStatus.PENDING_ADMIN_APPROVAL, OrderStatus.APPROVED, false, Role.ADMIN);
        put(t, OrderStatus.PENDING_ADMIN_APPROVAL, OrderStatus.REJECTED, false, Role.ADMIN);
        put(t, OrderStatus.PENDING_ADMIN_APPROVAL, OrderStatus.CANCELLED, false, Role.ADMIN);

        // Auto label on approval — SYSTEM (label service) or ADMIN.
        put(t, OrderStatus.APPROVED, OrderStatus.LABEL_GENERATED, true, Role.ADMIN);

        // Packing barcode scan (Req 8.2).
        put(t, OrderStatus.LABEL_GENERATED, OrderStatus.PACKED, false,
                Role.PACKING_USER, Role.ADMIN);

        // Handover to the delivery courier (Req 9.2, 9.3).
        put(t, OrderStatus.PACKED, OrderStatus.HANDED_TO_DELIVERY, false,
                Role.PACKING_USER, Role.ADMIN);

        // Dispatch (packer/admin) enqueues assignment which SYSTEM completes
        // (Req 9.5, 10.1); a failed/retried assignment self-retains via SYSTEM.
        put(t, OrderStatus.HANDED_TO_DELIVERY, OrderStatus.COURIER_ASSIGNED, true,
                Role.PACKING_USER, Role.ADMIN);
        put(t, OrderStatus.HANDED_TO_DELIVERY, OrderStatus.HANDED_TO_DELIVERY, true);

        // Courier pickup + webhook progressions — SYSTEM only (Req 10.2, 10.3).
        put(t, OrderStatus.COURIER_ASSIGNED, OrderStatus.DISPATCHED, true);
        put(t, OrderStatus.DISPATCHED, OrderStatus.IN_TRANSIT, true);
        put(t, OrderStatus.DISPATCHED, OrderStatus.OUT_FOR_DELIVERY, true);
        put(t, OrderStatus.DISPATCHED, OrderStatus.RTO, true);
        put(t, OrderStatus.DISPATCHED, OrderStatus.REDISPATCH, true);
        put(t, OrderStatus.IN_TRANSIT, OrderStatus.OUT_FOR_DELIVERY, true);
        put(t, OrderStatus.IN_TRANSIT, OrderStatus.DELIVERED, true);
        put(t, OrderStatus.IN_TRANSIT, OrderStatus.RTO, true);
        put(t, OrderStatus.IN_TRANSIT, OrderStatus.REDISPATCH, true);
        // New delivery outcomes (Req 11.1, 11.2) — SYSTEM only.
        put(t, OrderStatus.OUT_FOR_DELIVERY, OrderStatus.DELIVERED, true);
        put(t, OrderStatus.OUT_FOR_DELIVERY, OrderStatus.CUSTOMER_REJECTED, true);
        put(t, OrderStatus.OUT_FOR_DELIVERY, OrderStatus.DELIVERY_FAILED, true);
        put(t, OrderStatus.OUT_FOR_DELIVERY, OrderStatus.RTO, true);
        put(t, OrderStatus.OUT_FOR_DELIVERY, OrderStatus.REDISPATCH, true);

        // Settlement — ACCOUNTANT/ADMIN or SYSTEM (Req 16.1, 16.2).
        put(t, OrderStatus.DELIVERED, OrderStatus.CLOSED, true, Role.ACCOUNTANT, Role.ADMIN);
        put(t, OrderStatus.DELIVERED, OrderStatus.COD_COLLECTED, true, Role.ACCOUNTANT, Role.ADMIN);

        return Collections.unmodifiableMap(t);
    }

    private static void put(Map<Edge, Authorization> t, OrderStatus from, OrderStatus to,
                            boolean system, Role... roles) {
        Set<Role> roleSet = roles.length == 0 ? EnumSet.noneOf(Role.class) : EnumSet.of(roles[0], roles);
        t.put(new Edge(from, to), new Authorization(roleSet, system));
    }

    /**
     * Whether {@code role} may trigger the transition {@code from -> to}.
     *
     * @return {@code true} iff the transition appears in the authority table and
     *         the role is one of its permitted staff roles. Always {@code false}
     *         for a {@code null} role or a courier-only ({@code SYSTEM}) edge.
     */
    public boolean permits(OrderStatus from, OrderStatus to, Role role) {
        Authorization auth = lookup(from, to);
        return auth != null && auth.permitsRole(role);
    }

    /**
     * Whether the automatic {@code SYSTEM} actor may trigger {@code from -> to}
     * (courier assignment / webhook / poller driven transitions, Req 15.5).
     */
    public boolean permitsSystem(OrderStatus from, OrderStatus to) {
        Authorization auth = lookup(from, to);
        return auth != null && auth.system();
    }

    /**
     * Asserts that {@code role} may trigger {@code from -> to}, throwing
     * {@link UnauthorizedTransitionException} (HTTP 403) otherwise. The caller is
     * expected to have already established the transition's legality via
     * {@link OrderStatus#canTransitionTo(OrderStatus)}.
     */
    public void assertAuthorized(OrderStatus from, OrderStatus to, Role role) {
        if (!permits(from, to, role)) {
            throw new UnauthorizedTransitionException(
                    "Role " + role + " is not permitted to transition an order from "
                            + from + " to " + to + ".");
        }
    }

    /**
     * Asserts that the automatic {@code SYSTEM} actor may trigger
     * {@code from -> to}, throwing {@link UnauthorizedTransitionException}
     * otherwise.
     */
    public void assertSystemAuthorized(OrderStatus from, OrderStatus to) {
        if (!permitsSystem(from, to)) {
            throw new UnauthorizedTransitionException(
                    "SYSTEM is not permitted to transition an order from "
                            + from + " to " + to + ".");
        }
    }

    private Authorization lookup(OrderStatus from, OrderStatus to) {
        if (from == null || to == null) {
            return null;
        }
        return table.get(new Edge(from, to));
    }

    // ------------------------------------------------------------------
    // Context-aware overloads (spec shopify-quikshipx-order-sync, Req 9).
    //
    // Strictly ADDITIVE: the edge table and the four methods above are untouched.
    // Under TransitionContext.LEGACY every method below delegates to its
    // context-free counterpart, which is what keeps the pre-existing behaviour —
    // and the existing test suite — provably intact.
    // ------------------------------------------------------------------

    /**
     * Whether {@code role} may trigger {@code from -> to} given what else is known
     * about the order.
     *
     * <p>The one new rule: while an external courier owns an order's fulfilment, no
     * human may move it, whatever their role. Otherwise the Shifa status and the
     * courier portal's status would drift apart, and the courier's is the one the
     * parcel actually follows (Req 9.1).
     */
    public boolean permits(OrderStatus from, OrderStatus to, Role role, TransitionContext context) {
        TransitionContext ctx = context == null ? TransitionContext.LEGACY : context;
        if (ctx.courierManaged()) {
            return false;
        }
        // Not managed (including fallback mode) — the pre-existing role rules apply
        // verbatim (Req 9.3, 9.10).
        return permits(from, to, role);
    }

    /**
     * Whether the automatic {@code SYSTEM} actor may trigger {@code from -> to} given
     * what else is known about the order.
     *
     * <p>Two additions on top of the pre-existing table:
     * <ul>
     *   <li>a courier-managed order may be advanced into any {@linkplain ManagedStages
     *       managed stage}, because the courier is reporting where the parcel actually
     *       is and Shifa is mirroring it (Req 9.2);</li>
     *   <li>an order from an external storefront may be auto-approved, because the
     *       storefront already committed it and an internal approval gate would only
     *       stall a shipment the courier is already moving (Req 4.5). Never granted to
     *       an internal order, which must be approved by a human ADMIN (Req 4.7).</li>
     * </ul>
     */
    public boolean permitsSystem(OrderStatus from, OrderStatus to, TransitionContext context) {
        TransitionContext ctx = context == null ? TransitionContext.LEGACY : context;

        if (ctx.courierManaged() && ManagedStages.contains(to)) {
            return true;
        }
        if (ctx.isExternalStorefront()
                && from == OrderStatus.PENDING_ADMIN_APPROVAL
                && to == OrderStatus.APPROVED) {
            return true;
        }
        return permitsSystem(from, to);
    }

    /**
     * Asserts that {@code role} may trigger {@code from -> to} in this context,
     * throwing {@link UnauthorizedTransitionException} (HTTP 403) otherwise.
     */
    public void assertAuthorized(OrderStatus from, OrderStatus to, Role role, TransitionContext context) {
        if (!permits(from, to, role, context)) {
            TransitionContext ctx = context == null ? TransitionContext.LEGACY : context;
            if (ctx.courierManaged()) {
                throw new UnauthorizedTransitionException(
                        "This shipment is handled by the courier portal, so its status cannot be "
                                + "changed in Shifa (order is " + from + ").");
            }
            throw new UnauthorizedTransitionException(
                    "Role " + role + " is not permitted to transition an order from "
                            + from + " to " + to + ".");
        }
    }

    /**
     * Asserts that the automatic {@code SYSTEM} actor may trigger {@code from -> to}
     * in this context, throwing {@link UnauthorizedTransitionException} otherwise.
     */
    public void assertSystemAuthorized(OrderStatus from, OrderStatus to, TransitionContext context) {
        if (!permitsSystem(from, to, context)) {
            throw new UnauthorizedTransitionException(
                    "SYSTEM is not permitted to transition an order from "
                            + from + " to " + to + ".");
        }
    }
}
