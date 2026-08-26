package com.shifa.oms.ledger.autopost;

/**
 * The well-known <em>control ledgers</em> that decoupled auto-posting maps source business events
 * into (Reqs 8, 9, 10.4, 11).
 *
 * <p>Each value is a stable <strong>control role</strong> rather than a hard-coded ledger id. The
 * enum {@link #name()} IS the natural key stored in {@code ledger_accounts.control_key} (V54/V55),
 * so {@link ControlAccountResolver} can resolve a role to a concrete {@code ledger_accounts.id} at
 * runtime. Because the mapping lives in data (not code), an admin can re-point a control role at a
 * different ledger without a code change.
 *
 * <ul>
 *   <li>{@link #SUNDRY_DEBTORS} / {@link #SUNDRY_CREDITORS} — receivables / payables control.</li>
 *   <li>{@link #SALES} / {@link #PURCHASES} — trading income / expense.</li>
 *   <li>{@link #GST_OUTPUT} / {@link #GST_INPUT} — tax collected on sales / paid on purchases.</li>
 *   <li>{@link #CASH} / {@link #BANK} — settlement accounts.</li>
 *   <li>{@link #DEFAULT_EXPENSE} — fallback expense ledger for an unmapped expense category
 *       (Req 10.4).</li>
 * </ul>
 */
public enum ControlAccount {
    SUNDRY_DEBTORS,
    SUNDRY_CREDITORS,
    SALES,
    PURCHASES,
    GST_OUTPUT,
    GST_INPUT,
    CASH,
    BANK,
    DEFAULT_EXPENSE;

    /**
     * The natural key used to look this control role up in {@code ledger_accounts.control_key}. It is
     * exactly the enum constant name (e.g. {@code "SUNDRY_DEBTORS"}).
     *
     * @return the {@code control_key} string for this control role
     */
    public String key() {
        return name();
    }
}
