package com.shifa.oms.auth;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Focused unit tests for team-aware scoping in {@link SalespersonScopeResolver}.
 *
 * <p>Guards the security-critical rule behind the TEAM_LEAD role: a team lead may
 * only see the orders punched by the salespeople assigned to them, a salesperson
 * only their own, and an admin/accountant everything. A team lead with no team
 * must be scoped to NOTHING (empty set), never treated as unscoped.
 */
class TeamScopeResolverTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final SalespersonScopeResolver resolver = new SalespersonScopeResolver(userRepository);

    private static AuthPrincipal principal(long id, Role role) {
        return new AuthPrincipal(id, "user" + id, role);
    }

    @Test
    void salespersonIsScopedToOwnId() {
        assertThat(resolver.creatorScope(principal(7L, Role.SALESPERSON)))
                .contains(List.of(7L));
    }

    @Test
    void teamLeadIsScopedToTheirAssignedSalespeoplePlusSelf() {
        when(userRepository.findIdsByTeamLeadId(5L)).thenReturn(List.of(11L, 12L, 13L));

        // Own id first (the lead may punch orders themselves), then the team.
        assertThat(resolver.creatorScope(principal(5L, Role.TEAM_LEAD)))
                .contains(List.of(5L, 11L, 12L, 13L));
    }

    @Test
    void teamLeadWithNoTeamIsScopedToSelfOnly_notEverything() {
        when(userRepository.findIdsByTeamLeadId(5L)).thenReturn(List.of());

        // A lead with no team still sees their own orders, but never everything.
        assertThat(resolver.creatorScope(principal(5L, Role.TEAM_LEAD)))
                .contains(List.of(5L));
    }

    @Test
    void adminAndAccountantAreUnscoped() {
        assertThat(resolver.creatorScope(principal(1L, Role.ADMIN))).isEmpty();
        assertThat(resolver.creatorScope(principal(2L, Role.ACCOUNTANT))).isEmpty();
    }

    @Test
    void legacyCreatorConstraintStillSalespersonOnly() {
        // The single-id constraint is unchanged: only a salesperson is scoped;
        // a team lead resolves to empty here (team leads must use creatorScope).
        assertThat(resolver.creatorConstraint(principal(7L, Role.SALESPERSON))).contains(7L);
        assertThat(resolver.creatorConstraint(principal(5L, Role.TEAM_LEAD))).isEmpty();
        assertThat(resolver.creatorConstraint(principal(1L, Role.ADMIN))).isEmpty();
    }

    @Test
    void noArgResolverScopesTeamLeadToSelfOnly() {
        // The no-arg (test/legacy) resolver has no user lookup, so a team lead is
        // safely scoped to just their own id rather than accidentally unscoped.
        SalespersonScopeResolver bare = new SalespersonScopeResolver();
        assertThat(bare.creatorScope(principal(5L, Role.TEAM_LEAD)))
                .contains(List.of(5L));
    }
}
