package com.shifa.oms.ledger.statements.dto;

import com.shifa.oms.ledger.statements.BalanceSheetResult;
import com.shifa.oms.ledger.statements.domain.BalanceSheet;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

/**
 * Read view of the Balance Sheet ({@code GET /api/accounting/balance-sheet}, Financial Statements
 * Reqs 2, 3, 5, 7.1, 8, 9, 13.4).
 *
 * <p>Carries the two sides as recursive {@link StatementNodeResponse} trees (drill-down without a
 * separate endpoint), the injected {@link RetainedEarningsLine}, the side totals, the balancing
 * {@link #difference} and {@link #balanced} flag, and — when comparative — the prior-side totals.
 * Money is normalised to scale 2 in {@link #from}; enums serialise as {@code name()} and
 * {@link LocalDate} as {@code yyyy-MM-dd}.
 *
 * <p>The side totals and {@link #difference} carry the domain's <em>signed</em> (normal-side) totals
 * so that {@code difference == assetsTotal − liabilitiesAndEquityTotal} holds exactly (Req 3.4).
 * Prior grouped figures are aligned node-by-node inside the trees (by {@code groupId} / {@code
 * ledgerId}); the prior side totals are read off the prior statement (Req 7).
 *
 * @param asAtDate                       the As_At_Date — the resolved period's to-date (Req 1.5)
 * @param from                           the resolved reporting-period start date
 * @param to                             the resolved reporting-period end date (equals {@link #asAtDate})
 * @param financialYearId                the FY the period resolved from, or {@code null} for a range
 * @param comparative                    whether a comparative prior period was requested (Req 7)
 * @param assets                         the ASSET-nature top-level groups (drill-down tree)
 * @param assetsTotal                    the signed assets-side total (scale 2)
 * @param liabilitiesAndEquity           the LIABILITY + EQUITY top-level groups (drill-down tree)
 * @param retainedEarnings               the injected current-period net-profit line (Req 3.2)
 * @param liabilitiesAndEquityTotal      the signed liabilities-and-equity-side total (scale 2)
 * @param difference                     {@code assetsTotal − liabilitiesAndEquityTotal} (Req 3.4, scale 2)
 * @param balanced                       whether the two side totals are equal (Reqs 3.3, 13.2)
 * @param priorAssetsTotal               the prior assets-side total, or {@code null} when not comparative
 * @param priorLiabilitiesAndEquityTotal the prior liabilities-and-equity-side total, or {@code null}
 */
public record BalanceSheetResponse(
        LocalDate asAtDate, LocalDate from, LocalDate to, Long financialYearId, boolean comparative,
        List<StatementNodeResponse> assets, BigDecimal assetsTotal,
        List<StatementNodeResponse> liabilitiesAndEquity, RetainedEarningsLine retainedEarnings,
        BigDecimal liabilitiesAndEquityTotal, BigDecimal difference, boolean balanced,
        BigDecimal priorAssetsTotal, BigDecimal priorLiabilitiesAndEquityTotal) {

    private static final int MONEY_SCALE = 2;

    /**
     * Maps a {@link BalanceSheetResult} to its response view (Reqs 2, 3, 5, 7.1, 8), aligning the
     * comparative prior period node-by-node when it was requested.
     *
     * @param result the Balance Sheet service result
     * @return the response view with money at scale 2
     */
    public static BalanceSheetResponse from(BalanceSheetResult result) {
        BalanceSheet current = result.balanceSheet();
        BalanceSheet prior = result.comparative() ? result.priorBalanceSheet() : null;

        List<StatementNodeResponse> assets = StatementNodeResponse.fromList(
                current.assets().groups(), prior == null ? null : prior.assets().groups());
        List<StatementNodeResponse> liabilitiesAndEquity = StatementNodeResponse.fromList(
                current.liabilitiesAndEquity().groups(),
                prior == null ? null : prior.liabilitiesAndEquity().groups());
        RetainedEarningsLine retainedEarnings = RetainedEarningsLine.from(
                current.retainedEarnings(), prior == null ? null : prior.retainedEarnings());

        BigDecimal priorAssetsTotal = prior == null ? null : scale(prior.assets().signedTotal());
        BigDecimal priorLiabilitiesAndEquityTotal =
                prior == null ? null : scale(prior.liabilitiesAndEquity().signedTotal());

        return new BalanceSheetResponse(
                result.asAtDate(), result.from(), result.to(), result.financialYearId(), result.comparative(),
                assets, scale(current.assets().signedTotal()),
                liabilitiesAndEquity, retainedEarnings, scale(current.liabilitiesAndEquity().signedTotal()),
                scale(current.difference()), current.balanced(),
                priorAssetsTotal, priorLiabilitiesAndEquityTotal);
    }

    private static BigDecimal scale(BigDecimal value) {
        return value == null ? null : value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }
}
