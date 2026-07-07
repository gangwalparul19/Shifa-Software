package com.shifa.oms.auth;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * Central rule for salesperson-scoped queries (Req 5.5, 5.4).
 *
 * <p>A {@code SALESPERSON} may only see the orders they created, so any query on
 * their behalf must be constrained to {@code createdBy = currentUserId}. Every
 * other role — notably {@code ADMIN} — bypasses scoping and sees all records
 * (Req 5.4). Injecting the constraint here (rather than trusting a client-sent
 * filter) means scoping cannot be bypassed by request manipulation.
 *
 * <p>The order module (task 9) consumes {@link #creatorConstraint(AuthPrincipal)}
 * to add a {@code created_by} predicate at the repository layer. The generic
 * {@link #filter(AuthPrincipal, List, Function)} helper applies the same rule to
 * any in-memory collection and is used to prove the behavior under test until
 * persistence exists.
 */
@Component
public class SalespersonScopeResolver {

    /**
     * The {@code created_by} value a query for {@code principal} must be filtered
     * to, or {@link Optional#empty()} when the principal is not scoped (i.e. sees
     * everything, e.g. an Admin).
     *
     * @param principal the authenticated user the query runs on behalf of
     * @return the required {@code createdBy} id for a salesperson; empty otherwise
     */
    public Optional<Long> creatorConstraint(AuthPrincipal principal) {
        Objects.requireNonNull(principal, "principal");
        if (principal.role() == Role.SALESPERSON) {
            return Optional.of(principal.userId());
        }
        return Optional.empty();
    }

    /** Whether queries for this principal must be scoped to their own records. */
    public boolean isScoped(AuthPrincipal principal) {
        return creatorConstraint(principal).isPresent();
    }

    /**
     * Applies the scoping rule to an in-memory list: a salesperson sees only the
     * items whose creator id equals their own; every other role sees all items.
     *
     * @param principal   the authenticated user
     * @param items       the unfiltered items
     * @param creatorIdOf extracts the {@code createdBy} id from an item
     * @return the items visible to {@code principal}
     */
    public <T> List<T> filter(AuthPrincipal principal, List<T> items, Function<T, Long> creatorIdOf) {
        Objects.requireNonNull(items, "items");
        Objects.requireNonNull(creatorIdOf, "creatorIdOf");
        Optional<Long> constraint = creatorConstraint(principal);
        if (constraint.isEmpty()) {
            return List.copyOf(items);
        }
        Long requiredCreator = constraint.get();
        return items.stream()
                .filter(item -> requiredCreator.equals(creatorIdOf.apply(item)))
                .toList();
    }
}
