package com.shifa.oms.ledger.dto;

import com.shifa.oms.ledger.LedgerAccount;
import com.shifa.oms.ledger.domain.AccountNature;

/**
 * Read view of a postable ledger account ({@code GET /api/accounting/ledgers}, Reqs 2, 18.1).
 *
 * <p>A ledger account stores no nature; its {@link AccountNature} is <em>derived</em> from the owning
 * account group (Req 2.2) and must therefore be resolved and supplied to {@link #from} by the caller.
 * {@code controlKey} is the {@code ControlAccount} natural key for a seeded control ledger (e.g.
 * {@code SALES}) or {@code null} for an ordinary user-created ledger. The nature is serialised as its
 * {@code name()}.
 *
 * @param id              the ledger account id
 * @param name            the ledger account name
 * @param accountGroupId  the owning account group id
 * @param nature          the nature derived from the owning group
 * @param controlKey      the control-account natural key, or {@code null}
 * @param systemGenerated whether the ledger was seeded by the module
 */
public record LedgerAccountResponse(
        Long id,
        String name,
        Long accountGroupId,
        AccountNature nature,
        String controlKey,
        boolean systemGenerated
) {

    /**
     * Maps a persisted {@link LedgerAccount} to its response view, using the nature derived from the
     * ledger's owning account group (Req 2.2).
     *
     * @param ledger the ledger account
     * @param nature the nature resolved from the ledger's owning group
     */
    public static LedgerAccountResponse from(LedgerAccount ledger, AccountNature nature) {
        return new LedgerAccountResponse(
                ledger.getId(),
                ledger.getName(),
                ledger.getAccountGroupId(),
                nature,
                ledger.getControlKey(),
                ledger.isSystemGenerated());
    }
}
