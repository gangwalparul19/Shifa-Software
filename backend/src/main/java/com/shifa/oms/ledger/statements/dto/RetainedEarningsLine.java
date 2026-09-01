package com.shifa.oms.ledger.statements.dto;

import com.shifa.oms.ledger.domain.BalanceMath.SidedBalance;
import com.shifa.oms.ledger.domain.DrCr;
import com.shifa.oms.ledger.statements.domain.LedgerLine;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Response view of the synthetic retained-earnings (current-period Net_Profit) line injected into the
 * Balance Sheet's equity side (Financial Statements Reqs 3.1, 3.2, 8.1).
 *
 * <p>Presented as a {@link DrCr} side plus a non-negative magnitude (credit-positive for a profit);
 * money is normalised to scale 2. {@link #priorAmount} carries the prior-period retained-earnings
 * magnitude when a comparative period was requested, else {@code null} (Req 7).
 *
 * @param label       the presentation label (e.g. "Profit &amp; Loss A/c")
 * @param side        the side the line rests on ({@code CREDIT} for a profit, {@code DEBIT} for a loss)
 * @param amount      the non-negative magnitude (scale 2)
 * @param priorAmount the prior-period magnitude, or {@code null} when not comparative (Req 7)
 */
public record RetainedEarningsLine(String label, DrCr side, BigDecimal amount, BigDecimal priorAmount) {

    private static final int MONEY_SCALE = 2;

    /**
     * Maps the domain retained-earnings {@link LedgerLine} to its response view, aligning the
     * prior-period retained-earnings line (matched by its synthetic ledger id).
     *
     * @param line  the current-period retained-earnings line
     * @param prior the prior-period retained-earnings line, or {@code null} when not comparative
     * @return the response view with money at scale 2
     */
    public static RetainedEarningsLine from(LedgerLine line, LedgerLine prior) {
        SidedBalance balance = line.balance();
        BigDecimal priorAmount = prior == null ? null : scale(prior.balance().magnitude());
        return new RetainedEarningsLine(line.name(), balance.side(), scale(balance.magnitude()), priorAmount);
    }

    private static BigDecimal scale(BigDecimal value) {
        return value == null ? null : value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }
}
