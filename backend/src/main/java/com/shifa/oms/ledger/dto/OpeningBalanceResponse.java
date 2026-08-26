package com.shifa.oms.ledger.dto;

import com.shifa.oms.ledger.OpeningBalance;
import com.shifa.oms.ledger.OpeningBalanceService.OpeningBalanceCheck;
import com.shifa.oms.ledger.domain.DrCr;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Read view of a recorded ledger-account opening balance ({@code GET /api/accounting/opening-balances},
 * Req 3.1), together with the financial-year balancing check (Req 3.3).
 *
 * <p>The {@code amount} is presented at money scale 2 ({@code HALF_UP}); {@code side} is serialised
 * as its {@code name()}.
 *
 * @param id              the opening balance id
 * @param ledgerAccountId the ledger account the opening balance is for
 * @param financialYearId the financial year the opening balance applies to
 * @param amount          the opening balance magnitude (scale 2)
 * @param side            the debit-or-credit side of the opening balance
 */
public record OpeningBalanceResponse(
        Long id,
        Long ledgerAccountId,
        Long financialYearId,
        BigDecimal amount,
        DrCr side
) {

    private static final int MONEY_SCALE = 2;

    /** Maps a persisted {@link OpeningBalance} to its response view (amount at scale 2). */
    public static OpeningBalanceResponse from(OpeningBalance opening) {
        return new OpeningBalanceResponse(
                opening.getId(),
                opening.getLedgerAccountId(),
                opening.getFinancialYearId(),
                scale(opening.getAmount()),
                opening.getSide());
    }

    private static BigDecimal scale(BigDecimal value) {
        return value == null ? null : value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * The result of a financial year's opening-balance balancing check (Req 3.3): whether the opening
     * debits equal the opening credits, the two totals, and their signed difference
     * ({@code debitTotal - creditTotal}). All money at scale 2.
     *
     * @param balanced    whether the opening debits equal the opening credits
     * @param debitTotal  the sum of opening-balance debit amounts
     * @param creditTotal the sum of opening-balance credit amounts
     * @param difference  {@code debitTotal - creditTotal} (zero when balanced)
     */
    public record Check(
            boolean balanced,
            BigDecimal debitTotal,
            BigDecimal creditTotal,
            BigDecimal difference
    ) {

        /** Maps an {@link OpeningBalanceCheck} service result to its response view. */
        public static Check from(OpeningBalanceCheck check) {
            return new Check(
                    check.balanced(),
                    scale(check.debitTotal()),
                    scale(check.creditTotal()),
                    scale(check.difference()));
        }
    }
}
