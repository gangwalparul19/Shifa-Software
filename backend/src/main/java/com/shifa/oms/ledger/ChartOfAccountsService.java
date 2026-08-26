package com.shifa.oms.ledger;

import com.shifa.oms.common.ResourceNotFoundException;
import com.shifa.oms.common.ValidationException;
import com.shifa.oms.ledger.domain.AccountGroupHierarchy;
import com.shifa.oms.ledger.domain.AccountNature;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Chart-of-Accounts service: create/list account groups and postable ledger accounts
 * (General Ledger, Reqs 1.2–1.5, 2.1–2.4).
 *
 * <p>This is a <em>post-permission</em> service — creating or editing the Chart of Accounts is
 * restricted to ADMIN/ACCOUNTANT at the API layer ({@code LedgerController}); the CA role is
 * read-only. All correctness-critical hierarchy logic (the acyclic-parent rule) is delegated to the
 * pure {@link AccountGroupHierarchy} domain check so it stays free of Spring/JPA and is fully
 * property-testable.
 *
 * <p>Money and nature are never stored on a ledger account: a ledger's {@link AccountNature} is
 * always <em>derived</em> from its owning account group (Reqs 1.3, 2.2), giving a single source of
 * truth for classification.
 */
@Service
@Transactional
public class ChartOfAccountsService {

    private final AccountGroupRepository groupRepository;
    private final LedgerAccountRepository ledgerRepository;
    private final VoucherLineRepository voucherLineRepository;

    public ChartOfAccountsService(AccountGroupRepository groupRepository,
                                  LedgerAccountRepository ledgerRepository,
                                  VoucherLineRepository voucherLineRepository) {
        this.groupRepository = groupRepository;
        this.ledgerRepository = ledgerRepository;
        this.voucherLineRepository = voucherLineRepository;
    }

    /**
     * Create an account group.
     *
     * <p>When {@code parentGroupId} is supplied the child group inherits its parent's nature
     * (Req 1.3) and the supplied {@code nature} argument is ignored; when there is no parent the
     * group is a root and the supplied {@code nature} is required (Req 1.1). The request is rejected
     * with a {@link ValidationException} when it would introduce a cyclic parent (self or a
     * descendant, Req 1.4, via {@link AccountGroupHierarchy}) or when a sibling under the same parent
     * already carries the same name (Req 1.5).
     *
     * @param name          the group name (trimmed; required)
     * @param nature        the nature for a root group; ignored when a parent is given
     * @param parentGroupId the parent group id, or {@code null} for a root group
     * @return the persisted {@link AccountGroup}
     */
    public AccountGroup createGroup(String name, AccountNature nature, Long parentGroupId) {
        String cleanName = requireName(name);

        AccountNature effectiveNature;
        if (parentGroupId != null) {
            AccountGroup parent = groupRepository.findById(parentGroupId)
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Parent account group " + parentGroupId + " not found"));
            // Req 1.3: a child group carries the same nature as its parent.
            effectiveNature = parent.getNature();
            // Req 1.4: reject a cyclic parent (self / descendant) via the pure domain check.
            // A brand-new group has no id and no descendants yet, so a create can only ever close a
            // cycle if the surrounding hierarchy is already corrupt; the guard enforces the invariant
            // here and keeps this service correct if group re-parenting is added later.
            assertParentDoesNotCreateCycle(null, parentGroupId);
        } else {
            if (nature == null) {
                throw new ValidationException("A root account group requires a nature");
            }
            effectiveNature = nature;
        }

        // Req 1.5: reject a duplicate name under the same parent (NULL parent = the root bucket).
        boolean duplicate = parentGroupId == null
                ? groupRepository.existsByParentGroupIdIsNullAndName(cleanName)
                : groupRepository.existsByParentGroupIdAndName(parentGroupId, cleanName);
        if (duplicate) {
            throw new ValidationException(
                    "An account group named '" + cleanName + "' already exists under the same parent");
        }

        return groupRepository.save(new AccountGroup(cleanName, effectiveNature, parentGroupId, false));
    }

    /**
     * Create a postable ledger account under an account group.
     *
     * <p>The ledger records a reference to its owning group (Req 2.1); its nature is derived from
     * that group at read time (Req 2.2) and is therefore not stored. The request is rejected with a
     * {@link ValidationException} when a ledger with the same name already exists under the same
     * group (Req 2.3).
     *
     * @param name    the ledger name (trimmed; required)
     * @param groupId the owning account group id (required, must exist)
     * @return the persisted {@link LedgerAccount}
     */
    public LedgerAccount createLedger(String name, Long groupId) {
        String cleanName = requireName(name);
        if (groupId == null) {
            throw new ValidationException("A ledger account requires an account group");
        }
        // Req 2.1: the group must exist so the ledger's nature can be derived from it (Req 2.2).
        if (!groupRepository.existsById(groupId)) {
            throw new ResourceNotFoundException("Account group " + groupId + " not found");
        }
        // Req 2.3: reject a duplicate name under the same group.
        if (ledgerRepository.existsByAccountGroupIdAndName(groupId, cleanName)) {
            throw new ValidationException(
                    "A ledger account named '" + cleanName + "' already exists under the same account group");
        }
        return ledgerRepository.save(new LedgerAccount(cleanName, groupId, null, false));
    }

    /**
     * Delete a ledger account.
     *
     * <p>Rejected with a {@link ValidationException} when one or more posted voucher lines reference
     * the ledger (Req 2.4) — a ledger with accounting history is never removed, preserving the
     * tamper-evident record. A ledger with no postings is deleted.
     *
     * @param id the ledger account id
     */
    public void deleteLedger(Long id) {
        LedgerAccount ledger = ledgerRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Ledger account " + id + " not found"));
        // Req 2.4: a ledger referenced by any posted voucher line cannot be deleted.
        if (voucherLineRepository.existsByLedgerAccountId(id)) {
            throw new ValidationException(
                    "Ledger account '" + ledger.getName()
                            + "' cannot be deleted because it is referenced by posted voucher lines");
        }
        ledgerRepository.delete(ledger);
    }

    /**
     * Guard that attaching group {@code groupId} under {@code proposedParentId} keeps the
     * account-group hierarchy acyclic (Req 1.4). Delegates to the pure {@link AccountGroupHierarchy}
     * check over the current parent-of mapping and throws a {@link ValidationException} on a cycle.
     */
    private void assertParentDoesNotCreateCycle(Long groupId, Long proposedParentId) {
        if (proposedParentId == null) {
            return;
        }
        // A HashMap is used deliberately: Collectors.toMap rejects null values, but a root group's
        // parent id is null, so the mapping must tolerate null parents.
        Map<Long, Long> parentById = new HashMap<>();
        for (AccountGroup g : groupRepository.findAll()) {
            if (g.getId() != null) {
                parentById.put(g.getId(), g.getParentGroupId());
            }
        }
        Function<Long, Long> parentOf = parentById::get;
        long effectiveGroupId = groupId == null ? Long.MIN_VALUE : groupId;
        if (AccountGroupHierarchy.wouldCreateCycle(effectiveGroupId, proposedParentId, parentOf)) {
            throw new ValidationException(
                    "Account group cannot be its own parent or a descendant of itself");
        }
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new ValidationException("Name is required");
        }
        return name.trim();
    }
}
