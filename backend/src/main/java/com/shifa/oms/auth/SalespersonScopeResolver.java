package com.shifa.oms.auth;

import org.springframework.beans.factory.annotation.Autowired;
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
     * Looks up the salespeople assigned to a team lead. Optional so tests that
     * construct the resolver directly (no team-lead scenarios) keep working; when
     * absent, a team lead is scoped to nothing (safe — sees no orders rather than
     * all).
     */
    private final UserRepository userRepository;

    /** Test/legacy convenience: no team-lead lookup (team leads see nothing). */
    public SalespersonScopeResolver() {
        this(null);
    }

    @Autowired
    public SalespersonScopeResolver(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * The {@code created_by} value a query for {@code principal} must be filtered
     * to, or {@link Optional#empty()} when the principal is not scoped (i.e. sees
     * everything, e.g. an Admin).
     *
     * <p>Single-creator constraint — correct for a {@code SALESPERSON} (their own
     * id) only. A {@code TEAM_LEAD} needs a <em>set</em> of creator ids, so
     * team-aware call sites must use {@link #creatorScope(AuthPrincipal)} instead;
     * this method returns empty for a team lead and must not be used to scope
     * endpoints a team lead can reach.
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

    /**
     * The set of {@code created_by} ids a query for {@code principal} must be
     * restricted to, or {@link Optional#empty()} when the principal is unscoped
     * (ADMIN / ACCOUNTANT — sees everything).
     *
     * <ul>
     *   <li>{@code SALESPERSON} → a singleton of their own id (their own orders);</li>
     *   <li>{@code TEAM_LEAD} → the ids of the salespeople assigned to them (may
     *       be empty — a lead with no team sees nothing, never everything);</li>
     *   <li>every other role → empty {@link Optional} (unscoped).</li>
     * </ul>
     *
     * <p>A present-but-empty list means "scoped to nothing" and callers must
     * return no rows (NOT treat it as unscoped).
     */
    public Optional<List<Long>> creatorScope(AuthPrincipal principal) {
        Objects.requireNonNull(principal, "principal");
        return switch (principal.role()) {
            case SALESPERSON -> Optional.of(List.of(principal.userId()));
            case TEAM_LEAD -> Optional.of(teamMemberIds(principal.userId()));
            default -> Optional.empty();
        };
    }

    /** Ids of the salespeople assigned to a team lead; empty when unknown/none. */
    private List<Long> teamMemberIds(Long teamLeadId) {
        if (userRepository == null || teamLeadId == null) {
            return List.of();
        }
        return userRepository.findIdsByTeamLeadId(teamLeadId);
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
