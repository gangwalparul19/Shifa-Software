package com.shifa.oms.ledger;

import com.shifa.oms.ledger.domain.AccountNature;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Spring Data repository for {@link AccountGroup} (Chart-of-Accounts groups, Req 1.x).
 *
 * <p>The finders here back the hierarchy navigation, the acyclic-parent check, and the
 * duplicate-name-within-parent guard used by {@code ChartOfAccountsService}. The unique constraint
 * {@code (parent_group_id, name)} treats a {@code NULL} parent as a distinct bucket, so the root
 * (parent-less) uniqueness check uses the dedicated {@link #existsByParentGroupIdIsNullAndName}
 * derived query.
 */
public interface AccountGroupRepository extends JpaRepository<AccountGroup, Long> {

    /** All direct child groups of the given parent (empty for a leaf). */
    List<AccountGroup> findByParentGroupId(Long parentGroupId);

    /** All root (top-level, parent-less) groups. */
    List<AccountGroup> findByParentGroupIdIsNull();

    /** All groups of the given nature. */
    List<AccountGroup> findByNature(AccountNature nature);

    /** All account groups ordered by nature then name — the Chart-of-Accounts listing (Req 1, 18.1). */
    List<AccountGroup> findAllByOrderByNatureAscNameAsc();

    /** True when a child group of {@code parentGroupId} already carries {@code name} (Req 1.5). */
    boolean existsByParentGroupIdAndName(Long parentGroupId, String name);

    /** True when a root (parent-less) group already carries {@code name} (Req 1.5). */
    boolean existsByParentGroupIdIsNullAndName(String name);
}
