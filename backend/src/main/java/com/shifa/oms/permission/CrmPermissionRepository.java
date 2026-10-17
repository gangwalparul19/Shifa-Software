package com.shifa.oms.permission;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data repository for configurable {@link CrmPermission} grants (CRM
 * expansion Phase 3, Req 19).
 */
public interface CrmPermissionRepository extends JpaRepository<CrmPermission, Long> {

    /** All grants (for the admin management view). */
    List<CrmPermission> findAllByOrderByPermissionAscIdAsc();

    /** A specific user grant for a permission (overrides a role grant). */
    Optional<CrmPermission> findFirstByPermissionAndUserId(String permission, Long userId);

    /** A role grant for a permission. */
    Optional<CrmPermission> findFirstByPermissionAndRole(String permission, String role);

    /** All grants for a permission (used when resolving the effective set). */
    List<CrmPermission> findByPermission(String permission);
}
