package com.shifa.oms.ledger.statements.dto;

import com.shifa.oms.ledger.domain.AccountNature;
import com.shifa.oms.ledger.domain.BalanceMath.SidedBalance;
import com.shifa.oms.ledger.domain.DrCr;
import com.shifa.oms.ledger.statements.domain.GroupNode;
import com.shifa.oms.ledger.statements.domain.LedgerLine;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The recursive grouped-node tree used by every financial statement (Financial Statements Reqs 5.1,
 * 5.2, 5.3, 7.1, 8.1, 8.2, 13.4).
 *
 * <p>Each node reports its group subtotal as a {@link DrCr} side plus a non-negative {@link #amount}
 * magnitude (from the domain {@link GroupNode#subtotal() SidedBalance}), and carries its nested
 * {@link #childGroups} and leaf {@link #ledgers} so drill-down needs no separate endpoint. Money is
 * normalised to scale 2 in the {@code from(...)} mappers (mirroring {@code TrialBalanceResponse});
 * the {@link AccountNature}/{@link DrCr} enums serialise as their {@code name()}.
 *
 * <p>When a comparative prior period was requested, {@link #priorAmount} carries the prior-period
 * subtotal magnitude of the group aligned by {@code groupId}; it is {@code null} when not comparative
 * or when there is no matching prior group (Req 7). Prior alignment is by {@code groupId} for groups
 * and by {@code ledgerId} for ledger leaves.
 *
 * @param groupId     the account group id
 * @param name        the group name
 * @param nature      the group's nature
 * @param side        the side the subtotal rests on ({@code DEBIT}/{@code CREDIT})
 * @param amount      the non-negative subtotal magnitude (scale 2)
 * @param childGroups the nested child group nodes (drill-down), ordered by name
 * @param ledgers     the leaf ledger balances directly under this group (drill-down), ordered by name
 * @param priorAmount the prior-period subtotal magnitude of the same group, or {@code null} (Req 7)
 */
public record StatementNodeResponse(long groupId, String name, AccountNature nature, DrCr side,
                                    BigDecimal amount, List<StatementNodeResponse> childGroups,
                                    List<LedgerLineResponse> ledgers, BigDecimal priorAmount) {

    private static final int MONEY_SCALE = 2;

    /**
     * Maps a list of current-period top-level {@link GroupNode}s to their response tree, aligning
     * prior-period figures by {@code groupId} (groups) and {@code ledgerId} (ledger leaves) against
     * the supplied prior-period node forest.
     *
     * @param current the current-period top-level group nodes
     * @param prior   the prior-period top-level group nodes, or {@code null} when not comparative
     * @return the response node forest
     */
    public static List<StatementNodeResponse> fromList(List<GroupNode> current, List<GroupNode> prior) {
        Map<Long, GroupNode> priorGroups = null;
        Map<Long, LedgerLine> priorLedgers = null;
        if (prior != null) {
            priorGroups = new HashMap<>();
            priorLedgers = new HashMap<>();
            index(prior, priorGroups, priorLedgers);
        }
        Map<Long, GroupNode> pg = priorGroups;
        Map<Long, LedgerLine> pl = priorLedgers;
        return current.stream().map(node -> from(node, pg, pl)).toList();
    }

    /**
     * Maps a single {@link GroupNode} (and its subtree) to its response view, aligning the prior-period
     * subtotal by {@code groupId} and each ledger leaf by {@code ledgerId}.
     *
     * @param node         the current-period group node
     * @param priorGroups  prior-period groups by id, or {@code null} when not comparative
     * @param priorLedgers prior-period ledger lines by id, or {@code null} when not comparative
     * @return the response node
     */
    public static StatementNodeResponse from(GroupNode node, Map<Long, GroupNode> priorGroups,
                                             Map<Long, LedgerLine> priorLedgers) {
        SidedBalance subtotal = node.subtotal();
        BigDecimal priorAmount = null;
        if (priorGroups != null) {
            GroupNode prior = priorGroups.get(node.groupId());
            if (prior != null) {
                priorAmount = scale(prior.subtotal().magnitude());
            }
        }
        List<StatementNodeResponse> childGroups = node.childGroups().stream()
                .map(child -> from(child, priorGroups, priorLedgers))
                .toList();
        List<LedgerLineResponse> ledgers = node.ledgers().stream()
                .map(ledger -> LedgerLineResponse.from(ledger, priorLedgers))
                .toList();
        return new StatementNodeResponse(node.groupId(), node.name(), node.nature(), subtotal.side(),
                scale(subtotal.magnitude()), childGroups, ledgers, priorAmount);
    }

    /** Recursively indexes a group forest by {@code groupId} and its ledger leaves by {@code ledgerId}. */
    private static void index(List<GroupNode> nodes, Map<Long, GroupNode> groupsById,
                              Map<Long, LedgerLine> ledgersById) {
        for (GroupNode node : nodes) {
            groupsById.put(node.groupId(), node);
            for (LedgerLine ledger : node.ledgers()) {
                ledgersById.put(ledger.ledgerId(), ledger);
            }
            index(node.childGroups(), groupsById, ledgersById);
        }
    }

    static BigDecimal scale(BigDecimal value) {
        return value == null ? null : value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }
}
