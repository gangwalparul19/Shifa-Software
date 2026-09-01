package com.shifa.oms.ledger.statements.domain;

import com.shifa.oms.ledger.domain.BalanceMath.SidedBalance;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Objects;

/**
 * One side or section of a financial statement — a set of top-level rolled-up group nodes plus any
 * synthetic non-ledger lines, with the section total (Financial Statements, Reqs 5.1, 5.4, 8.2).
 *
 * <p>Examples of a section are the Balance Sheet's assets side, its liabilities-and-equity side, and
 * the Profit &amp; Loss income and expenses sides. Per Req 5.4 the sum of the top-level
 * {@link #groups} subtotals (plus any {@link #extraLines}) equals the section {@code signedTotal}.
 * {@code extraLines} carries synthetic lines that are not real ledger accounts — specifically the
 * Balance Sheet's injected retained-earnings (net-profit) line (Req 3.2).
 *
 * <p>The total is reported both as the internal {@code signedTotal} (relative to the section's
 * normal side, per {@link com.shifa.oms.ledger.domain.BalanceMath}) and as the display-oriented
 * {@link SidedBalance}. Money is scale-2 {@link BigDecimal} ({@code HALF_UP}) (Req 8.1); the lists
 * are defensively copied to keep the section immutable.
 *
 * @param title       the section title (e.g. "Assets", "Income")
 * @param groups      the top-level rolled-up group nodes, ordered by name (required, may be empty)
 * @param extraLines  synthetic non-ledger lines included in the section total (required, may be empty)
 * @param signedTotal the section total signed relative to the section's normal side (required)
 * @param total       the same total expressed as a display side + magnitude (required)
 */
public record StatementSection(String title, List<GroupNode> groups, List<LedgerLine> extraLines,
                               BigDecimal signedTotal, SidedBalance total) {

    /** Scale used for money, matching the codebase-wide {@code BigDecimal} scale-2 convention. */
    private static final int MONEY_SCALE = 2;

    public StatementSection {
        Objects.requireNonNull(signedTotal, "signedTotal");
        Objects.requireNonNull(total, "total");
        signedTotal = signedTotal.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        groups = List.copyOf(groups);
        extraLines = List.copyOf(extraLines);
    }
}
