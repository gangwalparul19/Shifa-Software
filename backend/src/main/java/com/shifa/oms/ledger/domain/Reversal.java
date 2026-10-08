package com.shifa.oms.ledger.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Line-by-line negation of a posted voucher (General Ledger, Req 6.3).
 *
 * <p>Posted vouchers are immutable; the only statutorily compliant way to correct one is to post a
 * <strong>reversing entry</strong> that exactly cancels it. This pure helper produces the reversing
 * side: given a posted voucher's {@link PostingLine posting lines}, it returns a negated counterpart
 * of each line — every {@link DrCr#DEBIT DEBIT} becomes a {@link DrCr#CREDIT CREDIT} of the same
 * amount and every {@code CREDIT} becomes a {@code DEBIT} of the same amount (via
 * {@link DrCr#opposite()}), keeping the same ledger reference, nature, and amount.
 *
 * <p>Because negation only flips each line's side while preserving amounts, the sum of debits and the
 * sum of credits are simply <em>swapped</em>. Therefore, whenever the original set is balanced (as
 * every posted voucher must be, per {@link DoubleEntry}), the reversing set is <strong>itself
 * balanced</strong>, and the combined net signed movement of the original plus its reversal is zero
 * for every ledger account involved.
 *
 * <p>The class is a stateless collection of pure static functions (no Spring, no JPA) mirroring the
 * rest of {@code ledger.domain}, so the reversal invariants are trivially property-testable without a
 * database.
 */
public final class Reversal {

    private Reversal() {
    }

    /**
     * Negate a single posting line: flip its {@link DrCr} side while preserving the ledger reference,
     * nature, and amount (Req 6.3). A debit of {@code amount} becomes a credit of {@code amount} and
     * vice versa.
     *
     * @param line the original posted line (must not be {@code null})
     * @return the negated counterpart posting on the opposite side
     */
    public static PostingLine negateLine(PostingLine line) {
        Objects.requireNonNull(line, "line");
        return new PostingLine(line.ledgerId(), line.nature(), line.drcr().opposite(), line.amount());
    }

    /**
     * Negate a posted voucher's lines line-by-line, preserving order (Req 6.3). The returned list is
     * a fresh, independent list; each element is the {@linkplain #negateLine(PostingLine) negated
     * counterpart} of the corresponding original line. When the input is balanced, the returned set
     * is balanced too (debit and credit totals are swapped).
     *
     * @param lines the original posted voucher's lines (must not be {@code null}; must contain no
     *              {@code null} elements)
     * @return the negated lines in the same order
     */
    public static List<PostingLine> negate(List<PostingLine> lines) {
        Objects.requireNonNull(lines, "lines");
        List<PostingLine> reversed = new ArrayList<>(lines.size());
        for (PostingLine line : lines) {
            reversed.add(negateLine(line));
        }
        return reversed;
    }

    /**
     * Build a reversing {@link DraftVoucher} for an original voucher (Req 6.3). The reversing draft
     * reuses the original's {@link VoucherType type} and {@code date}, carries the
     * {@linkplain #negate(List) negated lines}, and is given a non-blank narration that references the
     * original (satisfying the Req 5.7 narration requirement so the reversing draft posts cleanly).
     *
     * @param original the posted voucher's draft form (must not be {@code null})
     * @return a balanced reversing draft that cancels the original line by line
     */
    public static DraftVoucher negate(DraftVoucher original) {
        Objects.requireNonNull(original, "original");
        return new DraftVoucher(
                original.type(),
                original.date(),
                reversingNarration(original.narration()),
                negate(original.lines()));
    }

    private static String reversingNarration(String originalNarration) {
        if (originalNarration == null || originalNarration.isBlank()) {
            return "Reversal entry.";
        }
        return "Reversal of: " + originalNarration;
    }
}
