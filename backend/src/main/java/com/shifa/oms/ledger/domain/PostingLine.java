package com.shifa.oms.ledger.domain;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * One line within a draft voucher (General Ledger, Reqs 5.4, 5.5, 5.6).
 *
 * <p>A posting line references <strong>exactly one</strong> ledger account ({@code ledgerId}),
 * records that ledger's {@link AccountNature nature} (so downstream running-balance / trial-balance
 * math never needs a second lookup), and carries a <strong>single side</strong> ({@link DrCr}) with
 * a single {@code amount}. Because the record holds one {@code drcr} and one {@code amount}, a line
 * can never structurally carry <em>both</em> a debit and a credit; the "neither" and
 * "non-positive amount" cases are represented by a {@code null}/zero/negative {@code amount} and are
 * rejected by {@link DoubleEntry} rather than at construction.
 *
 * <p>Construction is deliberately <strong>total and exception-free</strong> for the money-bearing
 * fields ({@code ledgerId}, {@code amount}) so that invalid lines can be built and handed to
 * {@link DoubleEntry#validate(DraftVoucher)} for structured validation (this keeps the double-entry
 * rules trivially property-testable). Only the structural enum fields — {@code nature} and
 * {@code drcr} — are required to be non-null.
 *
 * @param ledgerId the id of the single ledger account this line posts against (may be {@code null},
 *                 which {@link DoubleEntry} reports as a missing ledger reference)
 * @param nature   the nature of the referenced ledger account (required)
 * @param drcr     the side this line posts on — debit or credit (required)
 * @param amount   the posting amount; must be strictly positive to be valid (a {@code null}, zero,
 *                 or negative amount is reported by {@link DoubleEntry})
 */
public record PostingLine(Long ledgerId, AccountNature nature, DrCr drcr, BigDecimal amount) {

    public PostingLine {
        Objects.requireNonNull(nature, "nature");
        Objects.requireNonNull(drcr, "drcr");
    }

    /** A debit line against {@code ledgerId} of the given {@code nature} for {@code amount}. */
    public static PostingLine debit(Long ledgerId, AccountNature nature, BigDecimal amount) {
        return new PostingLine(ledgerId, nature, DrCr.DEBIT, amount);
    }

    /** A credit line against {@code ledgerId} of the given {@code nature} for {@code amount}. */
    public static PostingLine credit(Long ledgerId, AccountNature nature, BigDecimal amount) {
        return new PostingLine(ledgerId, nature, DrCr.CREDIT, amount);
    }

    /** Whether this line posts on the debit side. */
    public boolean isDebit() {
        return drcr == DrCr.DEBIT;
    }

    /** Whether this line posts on the credit side. */
    public boolean isCredit() {
        return drcr == DrCr.CREDIT;
    }

    /** Whether this line references a ledger account (Req 5.4). */
    public boolean hasLedgerReference() {
        return ledgerId != null;
    }

    /** Whether this line carries a strictly positive amount (Req 5.6). */
    public boolean hasPositiveAmount() {
        return amount != null && amount.signum() > 0;
    }

    /**
     * The debit contribution of this line: {@code amount} when this is a debit line with a
     * non-null amount, otherwise {@link BigDecimal#ZERO}.
     */
    public BigDecimal debitAmount() {
        return isDebit() && amount != null ? amount : BigDecimal.ZERO;
    }

    /**
     * The credit contribution of this line: {@code amount} when this is a credit line with a
     * non-null amount, otherwise {@link BigDecimal#ZERO}.
     */
    public BigDecimal creditAmount() {
        return isCredit() && amount != null ? amount : BigDecimal.ZERO;
    }
}
