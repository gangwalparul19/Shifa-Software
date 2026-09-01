package com.shifa.oms.ledger.statements.domain;

import com.shifa.oms.ledger.domain.AccountNature;

import java.util.Objects;

/**
 * One Chart-of-Accounts group node fed into a financial-statement builder (Financial Statements,
 * Reqs 5.1, 5.2, 12.4).
 *
 * <p>A pure input value mirroring the Phase 1 {@code AccountGroup}: it carries the group's
 * {@link AccountNature} and an optional {@code parentGroupId} (a top-level group has a {@code null}
 * parent). The statement builders use these to reconstruct the account-group hierarchy and roll up
 * {@code Group_Subtotal}s bottom-up (Req 5.2), grouping ledger balances under their groups.
 *
 * @param parentGroupId the id of the parent group, or {@code null} for a top-level group
 * @param groupId       the account group id
 * @param name          the group name (for presentation and stable ordering)
 * @param nature        the group's nature (required)
 */
public record GroupInput(long groupId, String name, AccountNature nature, Long parentGroupId) {

    public GroupInput {
        Objects.requireNonNull(nature, "nature");
    }

    /** Whether this is a top-level group (it has no parent group). */
    public boolean isTopLevel() {
        return parentGroupId == null;
    }
}
