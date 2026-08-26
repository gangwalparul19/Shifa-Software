package com.shifa.oms.ledger.autopost;

import com.shifa.oms.common.ResourceNotFoundException;
import com.shifa.oms.ledger.LedgerAccount;
import com.shifa.oms.ledger.LedgerAccountRepository;
import org.springframework.stereotype.Service;

/**
 * Resolves a {@link ControlAccount} control role to the concrete {@link LedgerAccount} (and its id)
 * currently mapped to it, via the {@code ledger_accounts.control_key} natural key (Reqs 2.5, 8.1,
 * 9.1, 10.4, 11.1, 11.2).
 *
 * <p>The mapping is <strong>configurable</strong>: a control role is bound to whichever ledger carries
 * the matching {@code control_key}, so an admin can re-point (for example) {@code CASH} at a different
 * ledger without a code change. Because control ledgers can be re-pointed, a simple repository lookup
 * per call is used — correctness over caching. This keeps auto-posting always reading the current
 * mapping.
 *
 * <p>When a control role has no mapped ledger the resolver <em>fails loudly but safely</em> by throwing
 * {@link ResourceNotFoundException} — auto-posting must not silently post to the wrong account, and the
 * decoupled drainer converts such a failure into a FAILED outbox row + admin alert without ever touching
 * the source aggregate.
 */
@Service
public class ControlAccountResolver {

    private final LedgerAccountRepository ledgerAccountRepository;

    public ControlAccountResolver(LedgerAccountRepository ledgerAccountRepository) {
        this.ledgerAccountRepository = ledgerAccountRepository;
    }

    /**
     * Resolve the ledger currently mapped to the given control role.
     *
     * @param control the control role to resolve; must not be {@code null}
     * @return the mapped {@link LedgerAccount}
     * @throws ResourceNotFoundException when no ledger carries the role's {@code control_key}
     */
    public LedgerAccount resolveLedger(ControlAccount control) {
        if (control == null) {
            throw new ResourceNotFoundException("Control account is required");
        }
        return ledgerAccountRepository.findByControlKey(control.key())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No ledger is mapped to control account " + control.key()
                                + "; seed or configure the control ledger before auto-posting"));
    }

    /**
     * Resolve the id of the ledger currently mapped to the given control role.
     *
     * @param control the control role to resolve; must not be {@code null}
     * @return the mapped {@code ledger_accounts.id}
     * @throws ResourceNotFoundException when no ledger carries the role's {@code control_key}
     */
    public Long resolveLedgerId(ControlAccount control) {
        return resolveLedger(control).getId();
    }
}
