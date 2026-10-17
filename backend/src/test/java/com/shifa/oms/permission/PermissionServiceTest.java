package com.shifa.oms.permission;

import com.shifa.oms.auth.AuthPrincipal;
import com.shifa.oms.auth.Role;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Grant-resolution tests for {@link PermissionService} (CRM expansion Phase 3,
 * Task 20). Covers the pure {@link PermissionService#resolve} precedence (user
 * grant over role grant, else safe default) and that the safe-default seed
 * reproduces today's role gates.
 */
class PermissionServiceTest {

    // --- Pure resolution precedence ----------------------------------------

    @Test
    void userGrantOverridesRoleGrant() {
        // role grants false, user grants true → user wins
        assertTrue(PermissionService.resolve(PermissionService.ASSIGN_LEADS, Role.SALESPERSON, 7L,
                Optional.of(true), Optional.of(false)));
        // role grants true, user revokes → user wins (revoked)
        assertFalse(PermissionService.resolve(PermissionService.ASSIGN_LEADS, Role.TEAM_LEAD, 7L,
                Optional.of(false), Optional.of(true)));
    }

    @Test
    void roleGrantUsedWhenNoUserGrant() {
        assertTrue(PermissionService.resolve(PermissionService.MANAGE_TAGS, Role.SALESPERSON, 7L,
                Optional.empty(), Optional.of(true)));
        assertFalse(PermissionService.resolve(PermissionService.MANAGE_TAGS, Role.SALESPERSON, 7L,
                Optional.empty(), Optional.of(false)));
    }

    @Test
    void absentGrantFallsBackToSafeDefaultReproducingToday() {
        // Safe default: ASSIGN_LEADS held by ADMIN + TEAM_LEAD only.
        assertTrue(PermissionService.resolve(PermissionService.ASSIGN_LEADS, Role.TEAM_LEAD, 1L,
                Optional.empty(), Optional.empty()));
        assertFalse(PermissionService.resolve(PermissionService.ASSIGN_LEADS, Role.SALESPERSON, 1L,
                Optional.empty(), Optional.empty()));
        // Manage-* default = ADMIN only; a salesperson has none by default.
        assertFalse(PermissionService.resolve(PermissionService.MANAGE_PIPELINES, Role.SALESPERSON, 1L,
                Optional.empty(), Optional.empty()));
    }

    // --- has(principal, perm) over the repository --------------------------

    @Test
    void adminAlwaysHoldsEveryPermission() {
        PermissionService service = new PermissionService(mock(CrmPermissionRepository.class));
        AuthPrincipal admin = new AuthPrincipal(1L, "admin", Role.ADMIN);
        for (String perm : PermissionService.ALL_PERMISSIONS) {
            assertTrue(service.has(admin, perm), "admin holds " + perm);
        }
    }

    @Test
    void safeDefaultSeedPreservesTodaysGatesForNonAdmin() {
        PermissionService service = new PermissionService(mock(CrmPermissionRepository.class));
        AuthPrincipal teamLead = new AuthPrincipal(2L, "lead", Role.TEAM_LEAD);
        AuthPrincipal salesperson = new AuthPrincipal(3L, "sp", Role.SALESPERSON);

        assertTrue(service.has(teamLead, PermissionService.ASSIGN_LEADS));
        assertFalse(service.has(teamLead, PermissionService.MANAGE_PIPELINES));
        assertFalse(service.has(salesperson, PermissionService.ASSIGN_LEADS));
        assertFalse(service.has(salesperson, PermissionService.BULK_UPLOAD));
    }

    @Test
    void explicitUserGrantWidensAccessForNonAdmin() {
        CrmPermissionRepository repo = mock(CrmPermissionRepository.class);
        lenient().when(repo.findFirstByPermissionAndUserId(eq(PermissionService.ASSIGN_LEADS), anyLong()))
                .thenReturn(Optional.of(new CrmPermission(PermissionService.ASSIGN_LEADS, null, 3L, true)));
        when(repo.findFirstByPermissionAndRole(eq(PermissionService.ASSIGN_LEADS),
                eq(Role.SALESPERSON.name()))).thenReturn(Optional.empty());

        PermissionService service = new PermissionService(repo);
        AuthPrincipal salesperson = new AuthPrincipal(3L, "sp", Role.SALESPERSON);

        assertTrue(service.has(salesperson, PermissionService.ASSIGN_LEADS),
                "a granted salesperson is widened to ASSIGN_LEADS");
    }

    @Test
    void effectivePermissionsForTeamLeadMatchesDefaults() {
        PermissionService service = new PermissionService(mock(CrmPermissionRepository.class));
        AuthPrincipal teamLead = new AuthPrincipal(2L, "lead", Role.TEAM_LEAD);
        var effective = service.effectivePermissions(teamLead);
        assertTrue(effective.contains(PermissionService.ASSIGN_LEADS));
        assertFalse(effective.contains(PermissionService.MANAGE_TAGS));
    }
}
