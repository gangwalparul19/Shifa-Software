package com.shifa.oms.ledger.statements.dto;

import com.shifa.oms.ledger.domain.BalanceMath.SidedBalance;
import com.shifa.oms.ledger.domain.DrCr;
import com.shifa.oms.ledger.statements.domain.LedgerLine;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

/**
 * Response view of a leaf ledger balance within a statement group node — the bottom of a Tally-style
 * drill-down (Financial Statements Reqs 5.3, 8.1, 8.2, 13.4).
 *
 * <p>The balance is presented as a {@link DrCr} side plus a non-negative magnitude (from the domain
 * {@link LedgerLine#balance() SidedBalance}); money is normalised to scale 2 in {@link #from} as in
 * {@code TrialBalanceResponse}. When a comparative prior period was requested, {@link #priorAmount}
 * carries the prior-period magnitude of the same ledger (aligned by {@code ledgerId}); it is
 * {@code null} when not comparative or when the ledger has no matching prior line (Req 7).
 *
 * @param ledgerId    the ledger account id
 * @param name        the ledger account name
 * @param side        the side the balance rests on ({@code DEBIT}/{@code CREDIT})
 * @param amount      the non-negative closing magnitude (scale 2)
 * @param priorAmount the prior-period magnitude of the same ledger, or {@code null} (Req 7)
 */
public record LedgerLineResponse(long ledgerId, String name, DrCr side, BigDecimal amount,
                                 BigDecimal priorAmount) {

    private static final int MONEY_SCALE = 2;

    /**
     * Maps a domain {@link LedgerLine} to its response view, aligning the prior-period amount by
     * {@code ledgerId} against the supplied prior-period ledger index (which may be {@code null} when
     * no comparative period was requested).
     *
     * @param line          the current-period ledger line
     * @param priorByLedger the prior-period ledger lines by id, or {@code null} when not comparative
     * @return the response view with money at scale 2
     */
    public static LedgerLineResponse from(LedgerLine line, Map<Long, LedgerLine> priorByLedger) {
        SidedBalance balance = line.balance();
        BigDecimal priorAmount = null;
        if (priorByLedger != null) {
            LedgerLine prior = priorByLedger.get(line.ledgerId());
            if (prior != null) {
                priorAmount = scale(prior.balance().magnitude());
            }
        }
        return new LedgerLineResponse(line.ledgerId(), line.name(), balance.side(),
                scale(balance.magnitude()), priorAmount);
    }

    static BigDecimal scale(BigDecimal value) {
        return value == null ? null : value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }
}
