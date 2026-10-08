package com.shifa.oms.ledger.statements.domain;

import com.shifa.oms.ledger.domain.AccountNature;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * One ledger account's balance fed into a financial-statement builder (Financial Statements,
 * Reqs 2.1, 2.2, 5.1, 8.1, 12.4).
 *
 * <p>This is a pure input value — no Spring, no JPA. The {@code signedBalance} is the account's
 * balance <em>relative to its nature's {@linkplain AccountNature#normalSide() normal side}</em>,
 * following the Phase 1 {@link com.shifa.oms.ledger.domain.BalanceMath} sign convention: for a
 * Balance Sheet account it is the closing signed balance as at the As_At_Date, and for a Profit &amp;
 * Loss account it is the period's net signed movement (Req 4.1). Callers derive it via the same
 * {@code BalanceMath.signedDelta} calls the Trial Balance uses, so statements reconcile with the
 * Trial Balance by construction (Req 9).
 *
 * <p>Money is held as a scale-2 {@link BigDecimal} ({@code HALF_UP}), consistent with the Phase 1
 * ledger (Req 8.1); the compact constructor normalises the amount to scale 2.
 *
 * @param ledgerId      the ledger account id
 * @param name          the ledger account name (for presentation and stable ordering)
 * @param groupId       the id of the account group this ledger sits directly under
 * @param nature        the account's nature (required)
 * @param signedBalance the balance signed relative to the nature's normal side (required)
 */
public record LedgerBalanceInput(long ledgerId, String name, long groupId, AccountNature nature,
                                 BigDecimal signedBalance) {

    /** Scale used for money, matching the codebase-wide {@code BigDecimal} scale-2 convention. */
    private static final int MONEY_SCALE = 2;

    public LedgerBalanceInput {
        Objects.requireNonNull(nature, "nature");
        Objects.requireNonNull(signedBalance, "signedBalance");
        signedBalance = signedBalance.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }
}
