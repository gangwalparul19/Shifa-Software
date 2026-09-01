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

        // QuikShipX fast-forward: allotting a tracking id at approval hands the
        // order straight to the courier (SYSTEM only), skipping manual packing.
        put(t, OrderStatus.LABEL_GENERATED, OrderStatus.COURIER_ASSIGNED, true);

        // Handover to the delivery courier (Req 9.2, 9.3).
        put(t, OrderStatus.PACKED, OrderStatus.HANDED_TO_DELIVERY, false,
                Role.PACKING_USER, Role.ADMIN);

        // Dispatch (packer/admin) enqueues assignment which SYSTEM completes
        // (Req 9.5, 10.1); a failed/retried assignment self-retains via SYSTEM.
        put(t, OrderStatus.HANDED_TO_DELIVERY, OrderStatus.COURIER_ASSIGNED, true,
                Role.PACKING_USER, Role.ADMIN);
        put(t, OrderStatus.HANDED_TO_DELIVERY, OrderStatus.HANDED_TO_DELIVERY, true);

        // Courier pickup + webhook/tracking progressions — SYSTEM only (Req 10.2,
        // 10.3). Forward jumps from Courier_Assigned keep a QuikShipX tracking poll
        // from stalling when an intermediate scan is skipped between polls.
        put(t, OrderStatus.COURIER_ASSIGNED, OrderStatus.DISPATCHED, true);
        put(t, OrderStatus.COURIER_ASSIGNED, OrderStatus.IN_TRANSIT, true);
        put(t, OrderStatus.COURIER_ASSIGNED, OrderStatus.OUT_FOR_DELIVERY, true);
        put(t, OrderStatus.COURIER_ASSIGNED, OrderStatus.DELIVERED, true);
        put(t, OrderStatus.COURIER_ASSIGNED, OrderStatus.RTO, true);
        put(t, OrderStatus.COURIER_ASSIGNED, OrderStatus.REDISPATCH, true);
        put(t, OrderStatus.DISPATCHED, OrderStatus.IN_TRANSIT, true);
        put(t, OrderStatus.DISPATCHED, OrderStatus.OUT_FOR_DELIVERY, true);
        put(t, OrderStatus.DISPATCHED, OrderStatus.DELIVERED, true);
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
}
