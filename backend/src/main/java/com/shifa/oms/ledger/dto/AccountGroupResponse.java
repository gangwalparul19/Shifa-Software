package com.shifa.oms.ledger.dto;

import com.shifa.oms.ledger.AccountGroup;
import com.shifa.oms.ledger.domain.AccountNature;

/**
 * Read view of an account group ({@code GET /api/accounting/account-groups}, Reqs 1, 18.1).
 *
 * <p>The {@link AccountNature} is serialised as its {@code name()} (Jackson's default enum
 * rendering). {@code systemGenerated} marks the seeded default groups so the UI can present them
 * read-only.
 *
 * @param id              the account group id
 * @param name            the group name
 * @param nature          the group's nature (ASSET/LIABILITY/INCOME/EXPENSE/EQUITY)
 * @param parentGroupId   the parent group id, or {@code null} for a root group
 * @param systemGenerated whether the group was seeded by the module
 */
public record AccountGroupResponse(
        Long id,
        String name,
        AccountNature nature,
        Long parentGroupId,
        boolean systemGenerated
) {

    /** Maps a persisted {@link AccountGroup} to its response view. */
    public static AccountGroupResponse from(AccountGroup group) {
        return new AccountGroupResponse(
                group.getId(),
                group.getName(),
                group.getNature(),
                group.getParentGroupId(),
                group.isSystemGenerated());
    }
}
