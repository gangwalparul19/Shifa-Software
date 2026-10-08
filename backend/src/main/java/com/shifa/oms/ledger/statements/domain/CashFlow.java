package com.shifa.oms.ledger.statements.domain;

import com.shifa.oms.ledger.domain.AccountNature;
import com.shifa.oms.ledger.domain.BalanceMath;
import com.shifa.oms.ledger.domain.BalanceMath.SidedBalance;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * The pure direct-method Cash Flow statement over the Cash and Bank ledgers (Financial Statements,
 * Reqs 6.1, 6.2, 6.3, 6.4, 9.3).
 *
 * <p>The statement reads the combined movement of every {@code Cash_Bank_Ledger} (ASSET-nature
 * ledgers under the seeded {@code Cash-in-Hand} / {@code Bank Accounts} groups) over a reporting
 * period, presented as <em>opening → inflows → outflows → net → closing</em>:
 *
 * <ul>
 *   <li>{@code openingBalance} — the combined signed closing balance of the Cash/Bank ledgers at the
 *       day before the period start ({@code openingSigned}), shown as a display {@link SidedBalance}
 *       (Req 6.1);</li>
 *   <li>{@code inflows} — the total <strong>debit</strong> movement to the Cash/Bank ledgers in the
 *       period (cash in), a non-negative magnitude (Req 6.2);</li>
 *   <li>{@code outflows} — the total <strong>credit</strong> movement (cash out), a non-negative
 *       magnitude (Req 6.2);</li>
 *   <li>{@code netCashMovement} = {@code inflows − outflows} (Req 6.3);</li>
 *   <li>{@code closingBalance} = {@code openingSigned + netCashMovement}, shown as a display
 *       {@link SidedBalance} (Req 6.4).</li>
 * </ul>
 *
 * <p>Because the Cash/Bank ledgers are ASSET-nature, a debit increases the signed balance and a
 * credit decreases it, so the direct-method identity {@code closingSigned = openingSigned + inflows
 * − outflows} coincides exactly with the Phase 1 {@link BalanceMath} sign convention. As a
 * reconciliation check (Req 9.3), {@link #build} takes the independently-loaded ledger closing
 * balance ({@code closingSignedFromLedger}) and asserts it agrees with the computed closing — the
 * same number the Phase 1 ledger reports at the As_At_Date.
 *
 * <p>Money is scale-2 {@link BigDecimal} ({@code HALF_UP}) (Req 8.1). The class is immutable,
 * Spring-free, and built by a single total factory, so the cash-flow identity and reconciliation
 * invariants are directly property-testable.
 *
 * @param openingSigned   the combined signed opening balance (relative to ASSET's normal side)
 * @param openingBalance  the opening balance as a display side + magnitude (required)
 * @param inflows         the total in-period debit movement (cash in), a non-negative magnitude (required)
 * @param outflows        the total in-period credit movement (cash out), a non-negative magnitude (required)
 * @param netCashMovement {@code inflows − outflows} (required)
 * @param closingSigned   the combined signed closing balance ({@code openingSigned + netCashMovement})
 * @param closingBalance  the closing balance as a display side + magnitude (required)
 */
public record CashFlow(BigDecimal openingSigned, SidedBalance openingBalance,
                       BigDecimal inflows, BigDecimal outflows, BigDecimal netCashMovement,
                       BigDecimal closingSigned, SidedBalance closingBalance) {

    /** Scale used for money, matching the codebase-wide {@code BigDecimal} scale-2 convention. */
    private static final int MONEY_SCALE = 2;

    public CashFlow {
        Objects.requireNonNull(openingSigned, "openingSigned");
        Objects.requireNonNull(openingBalance, "openingBalance");
        Objects.requireNonNull(inflows, "inflows");
        Objects.requireNonNull(outflows, "outflows");
        Objects.requireNonNull(netCashMovement, "netCashMovement");
        Objects.requireNonNull(closingSigned, "closingSigned");
        Objects.requireNonNull(closingBalance, "closingBalance");
        openingSigned = openingSigned.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        inflows = inflows.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        outflows = outflows.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        netCashMovement = netCashMovement.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        closingSigned = closingSigned.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * Build the direct-method Cash Flow statement for a period from the combined Cash/Bank opening
     * signed balance, the in-period debit and credit movement magnitudes, and the independently
     * loaded closing signed balance used for reconciliation (Reqs 6.1–6.4, 9.3).
     *
     * @param openingSigned           the combined signed opening balance at the day before period start
     *                                (relative to ASSET's normal side, i.e. debit-positive)
     * @param inflows                 the total in-period debit movement to the Cash/Bank ledgers
     *                                (cash in); must be non-negative
     * @param outflows                the total in-period credit movement (cash out); must be non-negative
     * @param closingSignedFromLedger the closing signed balance the Phase 1 ledger reports at the
     *                                As_At_Date, used to reconcile the computed closing (Req 9.3)
     * @return the assembled {@link CashFlow}
     * @throws IllegalArgumentException if {@code inflows} or {@code outflows} is negative
     * @throws IllegalStateException    if the computed closing does not agree with
     *                                  {@code closingSignedFromLedger} (Req 9.3 reconciliation)
     */
    public static CashFlow build(BigDecimal openingSigned, BigDecimal inflows, BigDecimal outflows,
                                 BigDecimal closingSignedFromLedger) {
        Objects.requireNonNull(openingSigned, "openingSigned");
        Objects.requireNonNull(inflows, "inflows");
        Objects.requireNonNull(outflows, "outflows");
        Objects.requireNonNull(closingSignedFromLedger, "closingSignedFromLedger");

        BigDecimal opening = openingSigned.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        BigDecimal in = inflows.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        BigDecimal out = outflows.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        if (in.signum() < 0) {
            throw new IllegalArgumentException("inflows must be a non-negative magnitude: " + in);
        }
        if (out.signum() < 0) {
            throw new IllegalArgumentException("outflows must be a non-negative magnitude: " + out);
        }

        // Cash/Bank ledgers are ASSET-nature: debit (inflow) increases, credit (outflow) decreases
        // the signed balance, so the direct-method identity matches the BalanceMath convention.
        BigDecimal netCashMovement = in.subtract(out).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        BigDecimal computedClosing = opening.add(netCashMovement).setScale(MONEY_SCALE, RoundingMode.HALF_UP);

        // Reconcile the computed closing against the independently-loaded ledger closing (Req 9.3).
        BigDecimal ledgerClosing = closingSignedFromLedger.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        if (computedClosing.compareTo(ledgerClosing) != 0) {
            throw new IllegalStateException(
                    "Cash Flow closing does not reconcile with the ledger: computed " + computedClosing
                            + " (opening " + opening + " + net " + netCashMovement + ") vs ledger " + ledgerClosing);
        }

        SidedBalance openingBalance = BalanceMath.closingSide(AccountNature.ASSET, opening);
        SidedBalance closingBalance = BalanceMath.closingSide(AccountNature.ASSET, computedClosing);
        return new CashFlow(opening, openingBalance, in, out, netCashMovement, computedClosing, closingBalance);
    }
}
