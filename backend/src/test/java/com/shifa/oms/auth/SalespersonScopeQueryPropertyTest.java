package com.shifa.oms.auth;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.From;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based test for salesperson query scoping (design §6.7, §6.8,
 * §Correctness Properties (7), §10.1).
 *
 * Feature: role-based-order-workflow, Property 7: Salesperson queries return
 * only that salesperson's orders.
 *
 * Exercises the pure {@link SalespersonScopeResolver} in-memory (list, detail,
 * report, and dashboard queries all route through it): a salesperson sees
 * exactly their own orders; an admin/accountant query is unscoped. No Spring/DB,
 * no Mockito mocks of concrete classes.
 *
 * **Validates: Requirements 2.6, 5.5, 16.5**
 */
class SalespersonScopeQueryPropertyTest {

    /** A minimal order projection carrying just the creator id used for scoping. */
    record Order(long id, long createdBy) {
    }

    private final SalespersonScopeResolver resolver = new SalespersonScopeResolver();

    // Feature: role-based-order-workflow, Property 7: Salesperson queries return only that salesperson's orders
    // **Validates: Requirements 2.6, 5.5, 16.5**
    @Property(tries = 200)
    void salespersonSeesExactlyOwnOrders(
            @ForAll @Size(max = 60) List<@From("orders") Order> orders,
            @ForAll @IntRange(min = 1, max = 5) int salespersonId) {

        AuthPrincipal salesperson =
                new AuthPrincipal((long) salespersonId, "sales" + salespersonId, Role.SALESPERSON);

        // Reference set: exactly the orders this salesperson created.
        List<Order> expected = new ArrayList<>();
        for (Order o : orders) {
            if (o.createdBy() == salespersonId) {
                expected.add(o);
            }
        }

        List<Order> visible = resolver.filter(salesperson, orders, Order::createdBy);

        assertThat(resolver.isScoped(salesperson)).isTrue();
        assertThat(resolver.creatorConstraint(salesperson)).contains((long) salespersonId);
        // No others returned and none of theirs omitted.
        assertThat(visible).containsExactlyElementsOf(expected);
        assertThat(visible).allMatch(o -> o.createdBy() == salespersonId);
    }

    // Feature: role-based-order-workflow, Property 7: Salesperson queries return only that salesperson's orders
    // **Validates: Requirements 2.6, 5.5, 16.5**
    @Property(tries = 200)
    void adminAndAccountantQueriesAreUnscoped(
            @ForAll @Size(max = 60) List<@From("orders") Order> orders,
            @ForAll("unscopedRole") Role role) {

        AuthPrincipal principal = new AuthPrincipal(99L, "staff", role);

        assertThat(resolver.isScoped(principal)).isFalse();
        assertThat(resolver.creatorConstraint(principal)).isEmpty();
        // Unscoped: every order is visible regardless of creator.
        assertThat(resolver.filter(principal, orders, Order::createdBy))
                .containsExactlyElementsOf(orders);
    }

    @Provide
    Arbitrary<Order> orders() {
        Arbitrary<Long> id = Arbitraries.longs().between(1, 1_000_000);
        Arbitrary<Long> createdBy = Arbitraries.longs().between(1, 5);
        return Combinators.combine(id, createdBy).as(Order::new);
    }

    @Provide
    Arbitrary<Role> unscopedRole() {
        return Arbitraries.of(Role.ADMIN, Role.ACCOUNTANT);
    }
}
