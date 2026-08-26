package com.shifa.oms.ledger.dto;

import com.shifa.oms.ledger.domain.AccountNature;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Create payload for an account group ({@code POST /api/accounting/account-groups}, Reqs 1.1–1.5).
 *
 * <p>{@code name} is always required. {@code nature} is required only for a <em>root</em> group
 * (no parent); when {@code parentGroupId} is supplied the child inherits its parent's nature and the
 * supplied {@code nature} is ignored (Req 1.3). Because that "required unless a parent is given" rule
 * cannot be expressed as a single bean-validation constraint, {@code nature} is left optional here
 * and {@code ChartOfAccountsService.createGroup} enforces it (rejecting a root group with no nature).
 *
 * @param name          the group name (required, trimmed by the service)
 * @param nature        the nature for a root group; ignored when {@code parentGroupId} is supplied
 * @param parentGroupId the parent group id, or {@code null} for a root (top-level) group
 */
public record AccountGroupRequest(
        @NotBlank(message = "name is required")
        @Size(max = 150, message = "name must be at most 150 characters")
        String name,

        AccountNature nature,

        Long parentGroupId
) {
}
