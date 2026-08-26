package com.shifa.oms.ledger.domain;

/**
 * The two sides of a double-entry posting (General Ledger, Req 5.4, 7.x, 12.2).
 *
 * <p>Per double-entry accounting, every voucher line carries either a debit or a credit amount
 * (never both, never neither), and the total of debit amounts in a voucher equals the total of
 * credit amounts. This enum, together with {@link AccountNature}, encodes the sign convention that
 * the running balance and trial balance depend on.
 */
public enum DrCr {

    DEBIT,
    CREDIT;

    /**
     * The opposite side ({@code DEBIT} ↔ {@code CREDIT}). Used by reversal, which turns every
     * debit line into a credit of the same amount and vice versa.
     */
    public DrCr opposite() {
        return this == DEBIT ? CREDIT : DEBIT;
    }

    /**
     * The sign this side applies to a balance of the given {@code nature}: {@code +1} when posting on
     * this side <em>increases</em> a balance of that nature (i.e. this side is the nature's
     * {@linkplain AccountNature#normalSide() normal balance side}), and {@code -1} when it
     * <em>decreases</em> it.
     *
     * <p>For example, a {@code DEBIT} on an {@link AccountNature#ASSET} (whose normal side is debit)
     * returns {@code +1}, while a {@code CREDIT} on that same asset returns {@code -1}.
     *
     * @param nature the account nature the amount is posted against
     * @return {@code +1} if this side increases the balance, {@code -1} if it decreases it
     */
    public int signedFor(AccountNature nature) {
        return this == nature.normalSide() ? 1 : -1;
    }
}
