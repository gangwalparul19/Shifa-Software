package com.shifa.oms.statemachine;

import com.shifa.oms.auth.Role;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tuple;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Property-based test for per-transition role authority (design &sect;4.2).
 *
 * <p>Pure and in-memory: exercises {@link TransitionAuthority} against an
 * <em>independent</em> restatement of the design &sect;4.1 authority map, with no
 * Spring, database, or Mockito mocks of concrete classes. Each {@code @Property}
 * runs the jqwik default of 1000 tries (&ge; 100).
 */
class TransitionAuthorityPropertyTest {

    private final TransitionAuthority authority = new TransitionAuthority();

    /** A legal edge and the actors permitted to trigger it, per design §4.1. */
    private record Rule(OrderStatus from, OrderStatus to, Set<Role> roles, boolean system) {
    }

    private static final List<Rule> RULES = rules();

    private static List<Rule> rules() {
        List<Rule> r = new ArrayList<>();
        r.add(rule(OrderStatus.PENDING_ADMIN_APPROVAL, OrderStatus.APPROVED, false, Role.ADMIN));
        r.add(rule(OrderStatus.PENDING_ADMIN_APPROVAL, OrderStatus.REJECTED, false, Role.ADMIN));
        r.add(rule(OrderStatus.PENDING_ADMIN_APPROVAL, OrderStatus.CANCELLED, false, Role.ADMIN));
        r.add(rule(OrderStatus.APPROVED, OrderStatus.LABEL_GENERATED, true, Role.ADMIN));
        r.add(rule(OrderStatus.LABEL_GENERATED, OrderStatus.PACKED, false,
                Role.PACKING_USER, Role.ADMIN));
        r.add(rule(OrderStatus.PACKED, OrderStatus.HANDED_TO_DELIVERY, false,
                Role.PACKING_USER, Role.ADMIN));
        r.add(rule(OrderStatus.HANDED_TO_DELIVERY, OrderStatus.COURIER_ASSIGNED, true,
                Role.PACKING_USER, Role.ADMIN));
        r.add(rule(OrderStatus.HANDED_TO_DELIVERY, OrderStatus.HANDED_TO_DELIVERY, true));
        r.add(rule(OrderStatus.COURIER_ASSIGNED, OrderStatus.DISPATCHED, true));
        r.add(rule(OrderStatus.DISPATCHED, OrderStatus.IN_TRANSIT, true));
        r.add(rule(OrderStatus.DISPATCHED, OrderStatus.OUT_FOR_DELIVERY, true));
        r.add(rule(OrderStatus.DISPATCHED, OrderStatus.RTO, true));
        r.add(rule(OrderStatus.DISPATCHED, OrderStatus.REDISPATCH, true));
        r.add(rule(OrderStatus.IN_TRANSIT, OrderStatus.OUT_FOR_DELIVERY, true));
        r.add(rule(OrderStatus.IN_TRANSIT, OrderStatus.DELIVERED, true));
        r.add(rule(OrderStatus.IN_TRANSIT, OrderStatus.RTO, true));
        r.add(rule(OrderStatus.IN_TRANSIT, OrderStatus.REDISPATCH, true));
        r.add(rule(OrderStatus.OUT_FOR_DELIVERY, OrderStatus.DELIVERED, true));
        r.add(rule(OrderStatus.OUT_FOR_DELIVERY, OrderStatus.CUSTOMER_REJECTED, true));
        r.add(rule(OrderStatus.OUT_FOR_DELIVERY, OrderStatus.DELIVERY_FAILED, true));
        r.add(rule(OrderStatus.OUT_FOR_DELIVERY, OrderStatus.RTO, true));
        r.add(rule(OrderStatus.OUT_FOR_DELIVERY, OrderStatus.REDISPATCH, true));
        r.add(rule(OrderStatus.DELIVERED, OrderStatus.CLOSED, true,
                Role.ACCOUNTANT, Role.ADMIN));
        r.add(rule(OrderStatus.DELIVERED, OrderStatus.COD_COLLECTED, true,
                Role.ACCOUNTANT, Role.ADMIN));
        return r;
    }

    private static Rule rule(OrderStatus from, OrderStatus to, boolean system, Role... roles) {
        Set<Role> set = roles.length == 0 ? EnumSet.noneOf(Role.class)
                : EnumSet.copyOf(List.of(roles));
        return new Rule(from, to, set, system);
    }

    /** Fast lookup of the expected authority for a given legal edge. */
    private static final Map<OrderStatus, Map<OrderStatus, Rule>> BY_EDGE = indexRules();

    private static Map<OrderStatus, Map<OrderStatus, Rule>> indexRules() {
        Map<OrderStatus, Map<OrderStatus, Rule>> m = new EnumMap<>(OrderStatus.class);
        for (Rule r : RULES) {
            m.computeIfAbsent(r.from(), k -> new EnumMap<>(OrderStatus.class)).put(r.to(), r);
        }
        return m;
    }

    // Feature: role-based-order-workflow, Property 4: Every transition is authorized by role
    // **Validates: Requirements 1.5, 2.1, 2.2, 2.3, 2.4, 2.5, 12.5**
    @Property
    void everyLegalTransitionIsAuthorizedExactlyForItsPermittedRoles(
            @ForAll("legalEdges") Tuple.Tuple2<OrderStatus, OrderStatus> edge,
            @ForAll("roles") Role role) {
        OrderStatus from = edge.get1();
        OrderStatus to = edge.get2();
        Rule expected = BY_EDGE.get(from).get(to);

        boolean shouldPermitRole = expected.roles().contains(role);

        // permits(...) matches the specification exactly for this role.
        assertThat(authority.permits(from, to, role)).isEqualTo(shouldPermitRole);

        // assertAuthorized applies (does not throw) iff the role is permitted;
        // otherwise it is rejected (403) — role-not-allowed.
        if (shouldPermitRole) {
            assertThatCode(() -> authority.assertAuthorized(from, to, role))
                    .doesNotThrowAnyException();
        } else {
            assertThatThrownBy(() -> authority.assertAuthorized(from, to, role))
                    .isInstanceOf(UnauthorizedTransitionException.class);
        }

        // SYSTEM authority is governed independently and matches the table; the
        // courier-only edges are authorized for SYSTEM and for no staff role.
        assertThat(authority.permitsSystem(from, to)).isEqualTo(expected.system());
        if (expected.roles().isEmpty()) {
            assertThat(authority.permits(from, to, role)).isFalse();
            assertThat(authority.permitsSystem(from, to)).isTrue();
        }
    }

    // Feature: role-based-order-workflow, Property 4: Every transition is authorized by role
    // **Validates: Requirements 1.5, 2.1, 2.2, 2.3, 2.4, 2.5, 12.5**
    @Property
    void illegalEdgesAreNeverAuthorizedForAnyActor(
            @ForAll("statuses") OrderStatus from,
            @ForAll("statuses") OrderStatus to,
            @ForAll("roles") Role role) {
        boolean legal = BY_EDGE.getOrDefault(from, Map.of()).containsKey(to);
        if (legal) {
            return; // covered by the primary property
        }

        // No role and not SYSTEM may trigger an edge absent from the authority map.
        assertThat(authority.permits(from, to, role)).isFalse();
        assertThat(authority.permitsSystem(from, to)).isFalse();
        assertThatThrownBy(() -> authority.assertAuthorized(from, to, role))
                .isInstanceOf(UnauthorizedTransitionException.class);
        assertThatThrownBy(() -> authority.assertSystemAuthorized(from, to))
                .isInstanceOf(UnauthorizedTransitionException.class);
    }

    @Provide
    Arbitrary<Tuple.Tuple2<OrderStatus, OrderStatus>> legalEdges() {
        List<Tuple.Tuple2<OrderStatus, OrderStatus>> edges = new ArrayList<>();
        for (Rule r : RULES) {
            edges.add(Tuple.of(r.from(), r.to()));
        }
        return Arbitraries.of(edges);
    }

    @Provide
    Arbitrary<OrderStatus> statuses() {
        return Arbitraries.of(OrderStatus.values());
    }

    @Provide
    Arbitrary<Role> roles() {
        return Arbitraries.of(Role.values());
    }
}
