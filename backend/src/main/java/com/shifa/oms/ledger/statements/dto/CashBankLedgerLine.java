package com.shifa.oms.ledger.statements.dto;

import com.shifa.oms.ledger.statements.StatementLedgerLoader.CashBankLedgerActivity;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Response view of a single Cash/Bank ledger's direct-method split within the Cash Flow statement
 * drill-down (Financial Statements Reqs 5.3, 6.1, 6.2, 8.1, 13.4).
 *
 * <p>Cash/Bank ledgers are ASSET nature, so {@link #opening} and {@link #closing} are the signed
 * (debit-positive) balances and {@link #inflows}/{@link #outflows} are the in-period debit/credit
 * movement magnitudes. All money is normalised to scale 2.
 *
 * @param ledgerId the Cash/Bank ledger account id
 * @param name     the ledger account name
 * @param opening  the signed opening balance at the start of the period (scale 2)
 * @param inflows  the in-period cash-in (debit) movement magnitude (scale 2)
 * @param outflows the in-period cash-out (credit) movement magnitude (scale 2)
 * @param closing  the signed closing balance at the As_At_Date (scale 2)
 */
public record CashBankLedgerLine(long ledgerId, String name, BigDecimal opening, BigDecimal inflows,
                                 BigDecimal outflows, BigDecimal closing) {

    private static final int MONEY_SCALE = 2;

    /**
     * Maps a loader {@link CashBankLedgerActivity} to its response view (money at scale 2).
     *
     * @param activity the per-ledger Cash/Bank activity
     * @return the response view
     */
    public static CashBankLedgerLine from(CashBankLedgerActivity activity) {
        return new CashBankLedgerLine(activity.ledgerId(), activity.ledgerName(),
                scale(activity.openingSigned()), scale(activity.inflows()), scale(activity.outflows()),
                scale(activity.closingSigned()));
    }

    private static BigDecimal scale(BigDecimal value) {
        return value == null ? null : value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }
}
