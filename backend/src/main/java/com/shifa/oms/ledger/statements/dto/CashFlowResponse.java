package com.shifa.oms.ledger.statements.dto;

import com.shifa.oms.ledger.domain.DrCr;
import com.shifa.oms.ledger.statements.CashFlowService.CashFlowResult;
import com.shifa.oms.ledger.statements.domain.CashFlow;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

/**
 * Read view of the direct-method Cash Flow statement ({@code GET /api/accounting/cash-flow},
 * Financial Statements Reqs 6, 8, 9.3, 7.1).
 *
 * <p>Presents the {@code opening → inflows → outflows → net → closing} figures (opening/closing as a
 * {@link DrCr} side + magnitude), the per-Cash/Bank-ledger drill-down, and — when comparative — the
 * prior opening / net / closing figures. Money is normalised to scale 2 in {@link #from}; enums
 * serialise as {@code name()} and {@link LocalDate} as {@code yyyy-MM-dd}.
 *
 * @param from                 the resolved reporting-period start date
 * @param to                   the resolved reporting-period end date (the As_At_Date for the closing)
 * @param financialYearId      the FY the period resolved from, or {@code null} for a range
 * @param comparative          whether a comparative prior period was requested (Req 7)
 * @param openingSide          the side the opening balance rests on
 * @param openingBalance       the opening balance magnitude (scale 2, Req 6.1)
 * @param inflows              the total in-period cash-in (debit) movement (scale 2, Req 6.2)
 * @param outflows             the total in-period cash-out (credit) movement (scale 2, Req 6.2)
 * @param netCashMovement      {@code inflows − outflows} (scale 2, Req 6.3)
 * @param closingSide          the side the closing balance rests on
 * @param closingBalance       the closing balance magnitude (scale 2, Req 6.4)
 * @param ledgers              the per-Cash/Bank-ledger split (drill-down), ordered by ledger name
 * @param priorOpeningBalance  the prior-period opening magnitude, or {@code null} when not comparative
 * @param priorNetCashMovement the prior-period net cash movement, or {@code null} when not comparative
 * @param priorClosingBalance  the prior-period closing magnitude, or {@code null} when not comparative
 */
public record CashFlowResponse(
        LocalDate from, LocalDate to, Long financialYearId, boolean comparative,
        DrCr openingSide, BigDecimal openingBalance,
        BigDecimal inflows, BigDecimal outflows, BigDecimal netCashMovement,
        DrCr closingSide, BigDecimal closingBalance,
        List<CashBankLedgerLine> ledgers,
        BigDecimal priorOpeningBalance, BigDecimal priorNetCashMovement, BigDecimal priorClosingBalance) {

    private static final int MONEY_SCALE = 2;

    /**
     * Maps a {@link CashFlowResult} to its response view (Reqs 6, 8, 9.3, 7.1), attaching the
     * comparative prior figures when the prior period was requested.
     *
     * @param result the Cash Flow service result
     * @return the response view with money at scale 2
     */
    public static CashFlowResponse from(CashFlowResult result) {
        CashFlow current = result.cashFlow();
        CashFlow prior = result.comparative() ? result.priorCashFlow() : null;

        List<CashBankLedgerLine> ledgers = result.ledgers().stream()
                .map(CashBankLedgerLine::from)
                .toList();

        BigDecimal priorOpeningBalance = prior == null ? null : scale(prior.openingBalance().magnitude());
        BigDecimal priorNetCashMovement = prior == null ? null : scale(prior.netCashMovement());
        BigDecimal priorClosingBalance = prior == null ? null : scale(prior.closingBalance().magnitude());

        return new CashFlowResponse(
                result.from(), result.to(), result.financialYearId(), result.comparative(),
                current.openingBalance().side(), scale(current.openingBalance().magnitude()),
                scale(current.inflows()), scale(current.outflows()), scale(current.netCashMovement()),
                current.closingBalance().side(), scale(current.closingBalance().magnitude()),
                ledgers,
                priorOpeningBalance, priorNetCashMovement, priorClosingBalance);
    }

    private static BigDecimal scale(BigDecimal value) {
        return value == null ? null : value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }
}
