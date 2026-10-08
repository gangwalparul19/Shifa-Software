package com.shifa.oms.ledger.statements.domain;

import com.shifa.oms.ledger.domain.AccountNature;
import com.shifa.oms.ledger.domain.BalanceMath.SidedBalance;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Objects;

/**
 * A rolled-up account group in a financial statement — the recursive drill-down structure
 * (Financial Statements, Reqs 5.1, 5.2, 5.3, 13.4).
 *
 * <p>Each node reports its {@code Group_Subtotal} both as the internal {@code signedSubtotal}
 * (relative to the group nature's normal side, per {@link com.shifa.oms.ledger.domain.BalanceMath})
 * and as the display-oriented {@link SidedBalance}. Per Req 5.2 the subtotal equals the sum of the
 * balances of the {@link #ledgers} directly under the group plus the subtotals of its
 * {@link #childGroups}. A caller expands a node to reveal its child groups and ledger leaves, each
 * with its own balance, tracing any subtotal to the leaves that compose it (Reqs 5.3, 13.4).
 *
 * <p>Money is scale-2 {@link BigDecimal} ({@code HALF_UP}) (Req 8.1); the child and ledger lists are
 * defensively copied to keep the node immutable.
 *
 * @param groupId        the account group id
 * @param name           the group name
 * @param nature         the group's nature (required)
 * @param signedSubtotal the subtotal signed relative to the nature's normal side (required)
 * @param subtotal       the same subtotal expressed as a display side + magnitude (required)
 * @param childGroups    the nested child group nodes, ordered by name (required, may be empty)
 * @param ledgers        the ledger leaves directly under this group, ordered by name (required, may be empty)
 */
public record GroupNode(long groupId, String name, AccountNature nature, BigDecimal signedSubtotal,
                        SidedBalance subtotal, List<GroupNode> childGroups, List<LedgerLine> ledgers) {

    /** Scale used for money, matching the codebase-wide {@code BigDecimal} scale-2 convention. */
    private static final int MONEY_SCALE = 2;

    public GroupNode {
        Objects.requireNonNull(nature, "nature");
        Objects.requireNonNull(signedSubtotal, "signedSubtotal");
        Objects.requireNonNull(subtotal, "subtotal");
        signedSubtotal = signedSubtotal.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        childGroups = List.copyOf(childGroups);
        ledgers = List.copyOf(ledgers);
    }
}
