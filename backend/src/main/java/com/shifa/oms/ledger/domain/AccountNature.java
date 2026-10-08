package com.shifa.oms.ledger.domain;

/**
 * The primary classification of an account group (General Ledger, Req 1.1, 7.x).
 *
 * <p>Every account group — and, by derivation, every ledger account under it — is classified under
 * exactly one of these five natures. The nature determines whether a debit or a credit
 * <em>increases</em> the account's balance, captured here as the {@linkplain #normalSide() normal
 * balance side}:
 * <ul>
 *   <li>{@link #ASSET} and {@link #EXPENSE} normally carry a <strong>debit</strong> balance;</li>
 *   <li>{@link #LIABILITY}, {@link #INCOME}, and {@link #EQUITY} normally carry a
 *       <strong>credit</strong> balance.</li>
 * </ul>
 */
public enum AccountNature {
    ASSET(DrCr.DEBIT),
    EXPENSE(DrCr.DEBIT),
    LIABILITY(DrCr.CREDIT),
    INCOME(DrCr.CREDIT),
    EQUITY(DrCr.CREDIT);

    private final DrCr normalSide;

    AccountNature(DrCr normalSide) {
        this.normalSide = normalSide;
    }

    /**
     * The side on which an increase to a balance of this nature is recorded — {@code DEBIT} for
     * {@link #ASSET}/{@link #EXPENSE}, {@code CREDIT} for {@link #LIABILITY}/{@link #INCOME}/{@link #EQUITY}.
     */
    public DrCr normalSide() {
        return normalSide;
    }
}
