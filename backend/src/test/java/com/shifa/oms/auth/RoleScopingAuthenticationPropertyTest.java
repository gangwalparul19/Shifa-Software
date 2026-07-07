package com.shifa.oms.auth;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.Size;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based tests for role scoping and authentication enforcement
 * (Requirements 5.2, 5.3, 5.5).
 *
 * <p>These exercise the pure-logic core of authorization — {@link
 * EndpointAuthorization} (who may call a role-protected endpoint) and {@link
 * SalespersonScopeResolver} (which records a salesperson may see) — which is the
 * portion of Property 25 testable without a full HTTP stack. Each property runs
 * the jqwik default of 1000 tries (well above the required 100 iterations).
 */
class RoleScopingAuthenticationPropertyTest {

    private final EndpointAuthorization authorization = new EndpointAuthorization();
    private final SalespersonScopeResolver scoping = new SalespersonScopeResolver();

    /** A minimal order-like record: an id and the id of the salesperson who created it. */
    record OrderRow(long id, long createdBy) {
    }

    // Feature: shifa-herbal-remedies, Property 25: Role scoping and authentication enforcement
    // **Validates: Requirements 5.2, 5.3, 5.5**
    @Property
    void unauthenticatedRequestsToProtectedEndpointsAreDenied(
            @ForAll("nonEmptyRoleSets") Set<Role> allowedRoles) {
        // Req 5.2 — a request with no authenticated principal is always denied,
        // regardless of which roles the endpoint would otherwise permit.
        assertThat(authorization.isAllowed(Optional.empty(), allowedRoles)).isFalse();
    }

    // Feature: shifa-herbal-remedies, Property 25: Role scoping and authentication enforcement
    // **Validates: Requirements 5.2, 5.3, 5.5**
    @Property
    void authenticatedRequestsAreAllowedOnlyWhenRoleIsPermitted(
            @ForAll("principals") AuthPrincipal principal,
            @ForAll("roleSets") Set<Role> allowedRoles) {
        boolean allowed = authorization.isAllowed(Optional.of(principal), allowedRoles);
        // Req 5.3 — permitted exactly when the caller's role is in the endpoint's matrix.
        assertThat(allowed).isEqualTo(allowedRoles.contains(principal.role()));
    }

    // Feature: shifa-herbal-remedies, Property 25: Role scoping and authentication enforcement
    // **Validates: Requirements 5.2, 5.3, 5.5**
    @Property
    void salespersonQueriesReturnOnlyOwnOrdersAndAdminSeesAll(
            @ForAll("principals") AuthPrincipal principal,
            @ForAll @Size(max = 40) List<@net.jqwik.api.From("orders") OrderRow> orders) {
        List<OrderRow> visible = scoping.filter(principal, orders, OrderRow::createdBy);

        if (principal.role() == Role.SALESPERSON) {
            // Req 5.5 — a salesperson sees exactly the orders they created, nothing else.
            assertThat(visible).allMatch(o -> o.createdBy() == principal.userId());
            assertThat(visible).containsExactlyElementsOf(
                    orders.stream().filter(o -> o.createdBy() == principal.userId()).toList());
            assertThat(scoping.isScoped(principal)).isTrue();
            assertThat(scoping.creatorConstraint(principal)).contains(principal.userId());
        } else {
            // Req 5.4 — every other role (Admin included) bypasses scoping.
            assertThat(visible).containsExactlyElementsOf(orders);
            assertThat(scoping.isScoped(principal)).isFalse();
            assertThat(scoping.creatorConstraint(principal)).isEmpty();
        }
    }

    @Provide
    Arbitrary<AuthPrincipal> principals() {
        Arbitrary<Long> ids = Arbitraries.longs().between(1, 8);
        Arbitrary<String> usernames = Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(12);
        Arbitrary<Role> roles = Arbitraries.of(Role.values());
        return Combinators.combine(ids, usernames, roles).as(AuthPrincipal::new);
    }

    @Provide
    Arbitrary<OrderRow> orders() {
        Arbitrary<Long> ids = Arbitraries.longs().between(1, 1_000);
        Arbitrary<Long> creators = Arbitraries.longs().between(1, 8);
        return Combinators.combine(ids, creators).as(OrderRow::new);
    }

    @Provide
    Arbitrary<Set<Role>> roleSets() {
        return Arbitraries.of(Role.values()).set().ofMaxSize(Role.values().length);
    }

    @Provide
    Arbitrary<Set<Role>> nonEmptyRoleSets() {
        return Arbitraries.of(Role.values()).set().ofMinSize(1).ofMaxSize(Role.values().length);
    }
}
