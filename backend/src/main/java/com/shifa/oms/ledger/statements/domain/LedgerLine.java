package com.shifa.oms.ledger.statements.domain;

import com.shifa.oms.ledger.domain.BalanceMath.SidedBalance;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * A leaf ledger line within a statement group node — the bottom of a Tally-style drill-down
 * (Financial Statements, Reqs 5.3, 13.4).
 *
 * <p>Carries both the internal {@code signedAmount} (relative to the account nature's normal side,
 * per the Phase 1 {@link com.shifa.oms.ledger.domain.BalanceMath} convention) used for exact
 * summation, and the display-oriented {@link SidedBalance} (side + non-negative magnitude) produced
 * via {@code BalanceMath.closingSide} for presentation. Money is scale-2 {@link BigDecimal}
 * ({@code HALF_UP}) (Req 8.1); the compact constructor normalises {@code signedAmount}.
 *
 * @param ledgerId     the ledger account id
 * @param name         the ledger account name
 * @param signedAmount the balance signed relative to the nature's normal side (required)
 * @param balance      the same balance expressed as a display side + magnitude (required)
 */
public record LedgerLine(long ledgerId, String name, BigDecimal signedAmount, SidedBalance balance) {

    /** Scale used for money, matching the codebase-wide {@code BigDecimal} scale-2 convention. */
    private static final int MONEY_SCALE = 2;

    public LedgerLine {
        Objects.requireNonNull(signedAmount, "signedAmount");
        Objects.requireNonNull(balance, "balance");
        signedAmount = signedAmount.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }
}
