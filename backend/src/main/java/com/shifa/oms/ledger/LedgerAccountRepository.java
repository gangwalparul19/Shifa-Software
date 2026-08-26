package com.shifa.oms.ledger;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data repository for {@link LedgerAccount} (postable ledgers, Req 2.x).
 *
 * <p>The finders back the Chart-of-Accounts listing, the duplicate-name-within-group guard
 * (Req 2.3), and the control-account → ledger-id resolution used by auto-posting (via
 * {@link #findByControlKey}).
 */
public interface LedgerAccountRepository extends JpaRepository<LedgerAccount, Long> {

    /** All ledgers under the given account group. */
    List<LedgerAccount> findByAccountGroupId(Long accountGroupId);

    /** All ledger accounts ordered by name — the Chart-of-Accounts ledger listing (Req 2, 18.1). */
    List<LedgerAccount> findAllByOrderByNameAsc();

    /** True when a ledger under {@code accountGroupId} already carries {@code name} (Req 2.3). */
    boolean existsByAccountGroupIdAndName(Long accountGroupId, String name);

    /** The ledger mapped to the given control-account natural key, if any. */
    Optional<LedgerAccount> findByControlKey(String controlKey);
}
