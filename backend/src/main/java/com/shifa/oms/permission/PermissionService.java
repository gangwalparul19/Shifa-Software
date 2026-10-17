package com.shifa.oms.permission;

import com.shifa.oms.auth.AuthPrincipal;
import com.shifa.oms.auth.Role;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Configurable CRM permission resolution (CRM expansion Phase 3, Req 19; design
 * &sect;3.4), registered as the SpEL bean {@code @perms} so endpoints can widen
 * their base-role guard with {@code @PreAuthorize("hasRole('ADMIN') or
 * @perms.has(authentication,'ASSIGN_LEADS')")}.
 *
 * <p><strong>Resolution order</strong> for a permission and a caller:
 * <ol>
 *   <li>an explicit {@code user_id} grant ({@code granted} true/false) wins;</li>
 *   <li>else a {@code role} grant for the caller's role;</li>
 *   <li>else the SAFE DEFAULT ({@link #SAFE_DEFAULTS}) — which reproduces today's
 *       role gates, so a database with only the seeded defaults (or even no rows)
 *       behaves exactly as before the layer existed (Req 19.3).</li>
 * </ol>
 *
 * <p>Because every guarded endpoint keeps its base-role {@code @PreAuthorize} and
 * only {@code or}-adds {@code @perms.has(...)}, the permission layer can only
 * ever WIDEN access to a specifically-granted user — it never narrows today's
 * access.
 */
@Component("perms")
public class PermissionService {

    // --- Permission name constants -----------------------------------------
    public static final String ASSIGN_LEADS = "ASSIGN_LEADS";
    public static final String BULK_UPLOAD = "BULK_UPLOAD";
    public static final String MANAGE_PIPELINES = "MANAGE_PIPELINES";
    public static final String MANAGE_TAGS = "MANAGE_TAGS";
    public static final String MANAGE_SOURCES = "MANAGE_SOURCES";
    public static final String VIEW_ALL_REPORTS = "VIEW_ALL_REPORTS";

    /** The full set of defined CRM permissions (for the effective-set endpoint). */
    public static final List<String> ALL_PERMISSIONS = List.of(
            ASSIGN_LEADS, BULK_UPLOAD, MANAGE_PIPELINES, MANAGE_TAGS, MANAGE_SOURCES, VIEW_ALL_REPORTS);

    /**
     * Safe defaults reproducing today's role gates (used when no grant row
     * matches): maps a permission to the roles that hold it by default.
     */
    static final Map<String, Set<Role>> SAFE_DEFAULTS = buildSafeDefaults();

    private static Map<String, Set<Role>> buildSafeDefaults() {
        Map<String, Set<Role>> map = new LinkedHashMap<>();
        map.put(ASSIGN_LEADS, Set.of(Role.ADMIN, Role.TEAM_LEAD));
        map.put(BULK_UPLOAD, Set.of(Role.ADMIN));
        map.put(MANAGE_PIPELINES, Set.of(Role.ADMIN));
        map.put(MANAGE_TAGS, Set.of(Role.ADMIN));
        map.put(MANAGE_SOURCES, Set.of(Role.ADMIN));
        map.put(VIEW_ALL_REPORTS, Set.of(Role.ADMIN));
        return map;
    }

    private final CrmPermissionRepository repository;

    public PermissionService(CrmPermissionRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    /**
     * Pure resolution of a permission for a (role, userId) against configured
     * grants + the safe default. Package-visible so it is directly unit-testable
     * without a Spring context: a user grant wins over a role grant; absent both,
     * the safe default decides.
     */
    static boolean resolve(String permission, Role role, Long userId,
                           Optional<Boolean> userGrant, Optional<Boolean> roleGrant) {
        if (userGrant.isPresent()) {
            return userGrant.get();
        }
        if (roleGrant.isPresent()) {
            return roleGrant.get();
        }
        Set<Role> defaults = SAFE_DEFAULTS.getOrDefault(permission, Set.of());
        return role != null && defaults.contains(role);
    }

    /**
     * Whether the given principal holds the permission. ADMIN always holds every
     * CRM permission (it is the superuser role); otherwise resolves grants with
     * the safe-default fallback.
     */
    @Transactional(readOnly = true)
    public boolean has(AuthPrincipal principal, String permission) {
        if (principal == null || permission == null) {
            return false;
        }
        if (principal.role() == Role.ADMIN) {
            return true;
        }
        Optional<Boolean> userGrant = repository
                .findFirstByPermissionAndUserId(permission, principal.userId())
                .map(CrmPermission::isGranted);
        Optional<Boolean> roleGrant = repository
                .findFirstByPermissionAndRole(permission, principal.role().name())
                .map(CrmPermission::isGranted);
        return resolve(permission, principal.role(), principal.userId(), userGrant, roleGrant);
    }

    /**
     * The SpEL entry point used by {@code @PreAuthorize("@perms.has(authentication,
     * 'X')")}. Resolves the {@link AuthPrincipal} from the {@link Authentication}
     * and delegates to {@link #has(AuthPrincipal, String)}.
     */
    @Transactional(readOnly = true)
    public boolean has(Authentication authentication, String permission) {
        if (authentication == null
                || !(authentication.getPrincipal() instanceof AuthPrincipal principal)) {
            return false;
        }
        return has(principal, permission);
    }

    /** The caller's effective CRM permission set (for the frontend to hide actions). */
    @Transactional(readOnly = true)
    public Set<String> effectivePermissions(AuthPrincipal principal) {
        Set<String> result = new LinkedHashSet<>();
        for (String permission : ALL_PERMISSIONS) {
            if (has(principal, permission)) {
                result.add(permission);
            }
        }
        return result;
    }

    // --- Admin management ---------------------------------------------------

    /** All configured grants, in display order (admin management view). */
    @Transactional(readOnly = true)
    public List<CrmPermission> listGrants() {
        return repository.findAllByOrderByPermissionAscIdAsc();
    }

    /**
     * Upserts a grant for a role OR a user. When a matching grant exists (same
     * permission + role/user) its {@code granted} flag is updated; otherwise a new
     * grant is created. Exactly one of {@code role}/{@code userId} must be set.
     */
    @Transactional
    public CrmPermission grant(String permission, String role, Long userId, boolean granted) {
        if (permission == null || permission.isBlank()) {
            throw new com.shifa.oms.common.ValidationException("permission is required.");
        }
        boolean hasRole = role != null && !role.isBlank();
        boolean hasUser = userId != null;
        if (hasRole == hasUser) {
            throw new com.shifa.oms.common.ValidationException(
                    "Exactly one of role or userId must be supplied.");
        }
        String perm = permission.trim();
        Optional<CrmPermission> existing = hasUser
                ? repository.findFirstByPermissionAndUserId(perm, userId)
                : repository.findFirstByPermissionAndRole(perm, role.trim());
        CrmPermission grant = existing.orElseGet(() ->
                new CrmPermission(perm, hasRole ? role.trim() : null, hasUser ? userId : null, granted));
        grant.setGranted(granted);
        return repository.save(grant);
    }

    /** Removes a grant by id (reverting that role/user to the resolved default). */
    @Transactional
    public void revoke(Long id) {
        repository.deleteById(id);
    }
}
