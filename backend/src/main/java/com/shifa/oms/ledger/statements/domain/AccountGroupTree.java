package com.shifa.oms.ledger.statements.domain;

import com.shifa.oms.ledger.domain.AccountNature;
import com.shifa.oms.ledger.domain.BalanceMath;
import com.shifa.oms.ledger.domain.BalanceMath.SidedBalance;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The pure Tally-style account-group roll-up for the financial statements (Financial Statements,
 * Reqs 5.1, 5.2, 5.3, 5.4, 13.4).
 *
 * <p>Given a Chart-of-Accounts group forest ({@link GroupInput}s) and the closing/movement balances
 * of the ledger accounts under it ({@link LedgerBalanceInput}s), {@link #build(List, List, Set)}
 * reconstructs the hierarchy for a requested set of {@link AccountNature natures} and rolls up a
 * {@code Group_Subtotal} for every group <em>bottom-up</em>:
 *
 * <pre>{@code Group_Subtotal = Σ(signed amounts of ledgers directly under the group)
 *                             + Σ(child GroupNode subtotals)}</pre>
 *
 * <p>Amounts are carried internally as <strong>signed normal-side</strong> {@link BigDecimal}
 * (per the Phase 1 {@link BalanceMath} convention — assets/expenses debit-positive,
 * liabilities/income/equity credit-positive) and every leaf and subtotal is <em>also</em> exposed
 * as a display {@link SidedBalance} via {@link BalanceMath#closingSide}. Because the roll-up is an
 * exact partition of the same signed amounts, the sum of the returned top-level group subtotals
 * equals the section total (Req 5.4); {@link #signedTotal(List)} computes that sum for callers.
 *
 * <p>Child groups and ledger leaves are ordered by name (then id, for stability) so the presentation
 * is deterministic (Req 5.1). A group is included in the forest only when its nature is one of the
 * requested {@code include} natures; a group is a <em>root</em> of the forest when it has no parent
 * group of a requested nature (so slicing the hierarchy by nature — e.g. Assets vs
 * Liabilities-and-Equity for the Balance Sheet — yields the correct top-level groups). Ledgers whose
 * nature is not requested, or whose group is absent, are simply not attached (Req 2.4).
 *
 * <p>The class is immutable, Spring-free, and computed by a single total, exception-free factory, so
 * the roll-up and drill-down invariants are directly property-testable.
 */
public final class AccountGroupTree {

    /** Scale used for money, matching the codebase-wide {@code BigDecimal} scale-2 convention. */
    private static final int MONEY_SCALE = 2;

    private AccountGroupTree() {
    }

    /**
     * Build the forest of top-level {@link GroupNode}s for the requested natures, rolling up each
     * group's subtotal bottom-up from its directly-owned ledgers and its child groups (Reqs 5.1, 5.2,
     * 5.4, 13.4).
     *
     * @param groups  the Chart-of-Accounts group nodes (must not be {@code null}; {@code null}
     *                elements are ignored)
     * @param ledgers the ledger-account balances to place under their groups (must not be
     *                {@code null}; {@code null} elements are ignored)
     * @param include the natures whose groups form this section (must not be {@code null} or empty)
     * @return the top-level group nodes, ordered by name then id (immutable; may be empty)
     */
    public static List<GroupNode> build(List<GroupInput> groups, List<LedgerBalanceInput> ledgers,
                                        Set<AccountNature> include) {
        Objects.requireNonNull(groups, "groups");
        Objects.requireNonNull(ledgers, "ledgers");
        Objects.requireNonNull(include, "include");

        // Index the in-nature groups, their children, and the ledgers directly under each group.
        Map<Long, GroupInput> groupById = new HashMap<>();
        Map<Long, List<GroupInput>> childrenByParent = new HashMap<>();
        for (GroupInput group : groups) {
            if (group == null || !include.contains(group.nature())) {
                continue;
            }
            groupById.put(group.groupId(), group);
        }
        for (GroupInput group : groupById.values()) {
            Long parentId = group.parentGroupId();
            // Attach to the parent only when the parent is itself an in-nature group; otherwise the
            // group is a root of this section's forest.
            if (parentId != null && groupById.containsKey(parentId)) {
                childrenByParent.computeIfAbsent(parentId, k -> new ArrayList<>()).add(group);
            }
        }

        Map<Long, List<LedgerBalanceInput>> ledgersByGroup = new HashMap<>();
        for (LedgerBalanceInput ledger : ledgers) {
            if (ledger == null || !include.contains(ledger.nature())) {
                continue;
            }
            if (!groupById.containsKey(ledger.groupId())) {
                continue; // ledger's group is not part of this section
            }
            ledgersByGroup.computeIfAbsent(ledger.groupId(), k -> new ArrayList<>()).add(ledger);
        }

        // Roots: in-nature groups whose parent is not an in-nature group present in the forest.
        List<GroupInput> roots = new ArrayList<>();
        for (GroupInput group : groupById.values()) {
            Long parentId = group.parentGroupId();
            if (parentId == null || !groupById.containsKey(parentId)) {
                roots.add(group);
            }
        }
        roots.sort(GROUP_ORDER);

        List<GroupNode> forest = new ArrayList<>(roots.size());
        Set<Long> visited = new HashSet<>();
        for (GroupInput root : roots) {
            forest.add(buildNode(root, childrenByParent, ledgersByGroup, visited));
        }
        return List.copyOf(forest);
    }

    /**
     * The sum of the top-level group subtotals of a section (Req 5.4) — the section's signed total,
     * normalised to scale 2. Because each subtotal is an exact partition of the ledger balances, this
     * equals the section total the caller reports.
     *
     * @param topLevel the top-level group nodes of a section (must not be {@code null})
     * @return the signed section total at scale 2
     */
    public static BigDecimal signedTotal(List<GroupNode> topLevel) {
        Objects.requireNonNull(topLevel, "topLevel");
        BigDecimal total = BigDecimal.ZERO;
        for (GroupNode node : topLevel) {
            total = total.add(node.signedSubtotal());
        }
        return total.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private static GroupNode buildNode(GroupInput group, Map<Long, List<GroupInput>> childrenByParent,
                                       Map<Long, List<LedgerBalanceInput>> ledgersByGroup, Set<Long> visited) {
        // Guard against a malformed (cyclic) hierarchy so the pure builder can never loop forever.
        if (!visited.add(group.groupId())) {
            SidedBalance empty = BalanceMath.closingSide(group.nature(), BigDecimal.ZERO);
            return new GroupNode(group.groupId(), group.name(), group.nature(), BigDecimal.ZERO, empty,
                    List.of(), List.of());
        }

        // Direct ledger leaves, ordered by name then id.
        List<LedgerLine> ledgerLines = new ArrayList<>();
        BigDecimal signedSubtotal = BigDecimal.ZERO;
        List<LedgerBalanceInput> directLedgers =
                new ArrayList<>(ledgersByGroup.getOrDefault(group.groupId(), List.of()));
        directLedgers.sort(LEDGER_ORDER);
        for (LedgerBalanceInput ledger : directLedgers) {
            SidedBalance balance = BalanceMath.closingSide(ledger.nature(), ledger.signedBalance());
            ledgerLines.add(new LedgerLine(ledger.ledgerId(), ledger.name(), ledger.signedBalance(), balance));
            signedSubtotal = signedSubtotal.add(ledger.signedBalance());
        }

        // Child groups, recursively rolled up, ordered by name then id.
        List<GroupNode> childNodes = new ArrayList<>();
        List<GroupInput> children = new ArrayList<>(childrenByParent.getOrDefault(group.groupId(), List.of()));
        children.sort(GROUP_ORDER);
        for (GroupInput child : children) {
            GroupNode childNode = buildNode(child, childrenByParent, ledgersByGroup, visited);
            childNodes.add(childNode);
            signedSubtotal = signedSubtotal.add(childNode.signedSubtotal());
        }

        signedSubtotal = signedSubtotal.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        SidedBalance subtotal = BalanceMath.closingSide(group.nature(), signedSubtotal);
        return new GroupNode(group.groupId(), group.name(), group.nature(), signedSubtotal, subtotal,
                childNodes, ledgerLines);
    }

    private static final Comparator<GroupInput> GROUP_ORDER =
            Comparator.comparing(GroupInput::name, Comparator.nullsFirst(String.CASE_INSENSITIVE_ORDER))
                    .thenComparingLong(GroupInput::groupId);

    private static final Comparator<LedgerBalanceInput> LEDGER_ORDER =
            Comparator.comparing(LedgerBalanceInput::name, Comparator.nullsFirst(String.CASE_INSENSITIVE_ORDER))
                    .thenComparingLong(LedgerBalanceInput::ledgerId);
}
