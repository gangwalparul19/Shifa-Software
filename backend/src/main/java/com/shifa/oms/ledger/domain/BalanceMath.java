package com.shifa.oms.ledger.domain;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Pure sign-convention arithmetic for the General Ledger (Req 12.2).
 *
 * <p>A ledger account's running balance is tracked as a <em>signed</em> {@link BigDecimal} relative
 * to the account's {@linkplain AccountNature#normalSide() normal balance side}: a non-negative
 * signed balance means the account carries its normal-side balance, and a negative signed balance
 * means it carries the opposite side. Every posting line adjusts that signed balance by a
 * {@linkplain #signedDelta(AccountNature, DrCr, BigDecimal) signed delta} that is {@code +amount}
 * when the line is posted on the account's normal side and {@code -amount} otherwise (via
 * {@link DrCr#signedFor(AccountNature)}).
 *
 * <p>All operations are additive {@code BigDecimal} arithmetic and therefore introduce no rounding;
 * callers supply amounts already at the ledger's money scale (2, {@code HALF_UP}). This class is
 * final with a private constructor and only static methods — it is Spring-free and fully
 * unit-testable.
 */
public final class BalanceMath {

    private BalanceMath() {
    }

    /**
     * The signed contribution a single posting line makes to a balance of the given nature:
     * {@code amount} when {@code drcr} is the nature's {@linkplain AccountNature#normalSide() normal
     * side}, and {@code amount.negate()} otherwise.
     *
     * @param nature the account nature the amount is posted against
     * @param drcr   the side (debit or credit) the amount is posted on
     * @param amount the (non-signed) posting amount
     * @return {@code +amount} if the line increases the balance, {@code -amount} if it decreases it
     */
    public static BigDecimal signedDelta(AccountNature nature, DrCr drcr, BigDecimal amount) {
        Objects.requireNonNull(nature, "nature");
        Objects.requireNonNull(drcr, "drcr");
        Objects.requireNonNull(amount, "amount");
        return drcr.signedFor(nature) >= 0 ? amount : amount.negate();
    }

    /**
     * Applies a posting line to a running signed balance, returning the new signed balance
     * ({@code runningSigned + signedDelta(nature, drcr, amount)}).
     *
     * @param runningSigned the signed running balance before this line
     * @param nature        the account nature the amount is posted against
     * @param drcr          the side the amount is posted on
     * @param amount        the (non-signed) posting amount
     * @return the signed running balance after applying the line
     */
    public static BigDecimal applyToBalance(BigDecimal runningSigned, AccountNature nature, DrCr drcr,
                                            BigDecimal amount) {
        Objects.requireNonNull(runningSigned, "runningSigned");
        return runningSigned.add(signedDelta(nature, drcr, amount));
    }

    /**
     * Resolves a signed balance into the reporting side and magnitude a statement or trial balance
     * would show: a non-negative signed balance is the nature's {@linkplain AccountNature#normalSide()
     * normal side} with magnitude {@code |signedBalance|}; a negative signed balance is the opposite
     * side with the same magnitude.
     *
     * @param nature        the account nature
     * @param signedBalance the signed balance (relative to the nature's normal side)
     * @return the {@link SidedBalance} (side + non-negative magnitude) for reporting
     */
    public static SidedBalance closingSide(AccountNature nature, BigDecimal signedBalance) {
        Objects.requireNonNull(nature, "nature");
        Objects.requireNonNull(signedBalance, "signedBalance");
        DrCr side = signedBalance.signum() < 0 ? nature.normalSide().opposite() : nature.normalSide();
        return new SidedBalance(side, signedBalance.abs());
    }

    /**
     * A balance expressed as a reporting side and a non-negative magnitude — the shape a Ledger
     * statement, Trial Balance, or closing-balance carry-forward reports (Reqs 12, 14, 3.4).
     *
     * @param side      the side the balance rests on ({@code DEBIT} or {@code CREDIT})
     * @param magnitude the non-negative absolute balance amount
     */
    public record SidedBalance(DrCr side, BigDecimal magnitude) {
        public SidedBalance {
            Objects.requireNonNull(side, "side");
            Objects.requireNonNull(magnitude, "magnitude");
        }
    }
}
