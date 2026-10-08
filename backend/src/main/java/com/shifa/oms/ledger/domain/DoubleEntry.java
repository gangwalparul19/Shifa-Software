package com.shifa.oms.ledger.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * The core double-entry validator (General Ledger, Reqs 5.1, 5.2, 5.3, 5.4, 5.5, 5.6, 5.7).
 *
 * <p>This is the compliance heart the Chartered Accountant must trust: given a {@link DraftVoucher}
 * it decides whether the draft may be posted as a balanced double entry, and — crucially — it does
 * so <strong>without throwing</strong>. {@link #validate(DraftVoucher)} returns a structured
 * {@link Result} carrying an ordered {@link ValidationResult} (an {@code ok} flag plus a
 * human-readable, ordered list of violation messages) together with the {@link BalanceCheck}
 * (debit total, credit total, and their difference). The voucher service turns a non-ok result into
 * a {@code common.ValidationException}, using the {@link BalanceCheck} to render the exact
 * debit/credit/difference message Req 5.3 requires. Keeping the domain exception-free makes every
 * rule trivially property-testable.
 *
 * <p>Rules enforced (all of Req 5 except reference assignment, Req 5.8, which is a service concern):
 * <ul>
 *   <li><strong>5.7</strong> — a voucher date, a voucher type, and a narration are present;</li>
 *   <li><strong>5.1</strong> — the voucher contains at least two lines;</li>
 *   <li><strong>5.4</strong> — each line references exactly one ledger account;</li>
 *   <li><strong>5.5/5.6</strong> — each line carries a single side (structural) with a strictly
 *       positive amount (a {@code null}/zero/negative amount means the line carries "neither");</li>
 *   <li><strong>5.2/5.3</strong> — the sum of debit amounts equals the sum of credit amounts,
 *       reported with exact totals on failure.</li>
 * </ul>
 *
 * <p>Because {@link PostingLine} models a single {@link DrCr} side plus a single amount, a line can
 * never structurally carry <em>both</em> a debit and a credit; that half of Req 5.5 holds by
 * construction, and this validator enforces the remaining "amount present and positive" half.
 */
public final class DoubleEntry {

    /** Scale used for money totals, matching the codebase-wide {@code BigDecimal} scale-2 convention. */
    private static final int MONEY_SCALE = 2;

    private DoubleEntry() {
    }

    /**
     * The debit total, credit total, and their difference for a draft voucher (Req 5.2, 5.3).
     *
     * @param debitTotal  the sum of every line's {@linkplain PostingLine#debitAmount() debit amount}
     * @param creditTotal the sum of every line's {@linkplain PostingLine#creditAmount() credit amount}
     * @param difference  {@code debitTotal - creditTotal}; zero exactly when the voucher balances
     */
    public record BalanceCheck(BigDecimal debitTotal, BigDecimal creditTotal, BigDecimal difference) {

        /** Whether debit and credit totals are equal (difference is zero). */
        public boolean balanced() {
            return difference.signum() == 0;
        }
    }

    /**
     * The outcome of validating a draft (Req 5.3): an {@code ok} flag and an ordered, immutable list
     * of human-readable violation messages. {@code ok} is {@code true} exactly when there are no
     * violations.
     */
    public record ValidationResult(boolean ok, List<String> violations) {

        public ValidationResult(boolean ok, List<String> violations) {
            this.violations = List.copyOf(violations);
            this.ok = ok;
        }

        static ValidationResult from(List<String> violations) {
            return new ValidationResult(violations.isEmpty(), violations);
        }
    }

    /**
     * The full structured result of {@link #validate(DraftVoucher)}: the {@link ValidationResult}
     * and the {@link BalanceCheck} computed for the draft.
     *
     * @param validation the ok flag and ordered violation messages
     * @param balance    the debit/credit/difference totals
     */
    public record Result(ValidationResult validation, BalanceCheck balance) {

        /** Convenience delegate for {@code validation().ok()}. */
        public boolean ok() {
            return validation.ok();
        }

        /** Convenience delegate for {@code validation().violations()}. */
        public List<String> violations() {
            return validation.violations();
        }
    }

    /**
     * Compute the debit and credit totals (and their difference) for a draft's lines, using each
     * line's {@link DrCr} side to decide which side it contributes to (Req 5.2). This is the helper
     * the service uses to render the Req 5.3 imbalance message. {@code null} lines and {@code null}
     * amounts contribute zero; all totals are normalised to scale {@value #MONEY_SCALE}.
     *
     * @param draft the draft voucher (must not be {@code null})
     * @return the debit total, credit total, and {@code debitTotal - creditTotal}
     */
    public static BalanceCheck totals(DraftVoucher draft) {
        BigDecimal debit = BigDecimal.ZERO;
        BigDecimal credit = BigDecimal.ZERO;
        for (PostingLine line : draft.lines()) {
            if (line == null) {
                continue;
            }
            debit = debit.add(line.debitAmount());
            credit = credit.add(line.creditAmount());
        }
        debit = debit.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        credit = credit.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        return new BalanceCheck(debit, credit, debit.subtract(credit));
    }

    /**
     * Validate a draft voucher against all of the double-entry rules (Reqs 5.1–5.7) without throwing.
     *
     * <p>Violations are reported in a stable order: voucher-level checks (date, type, narration,
     * line count) first, then per-line checks in line order, then the balance check. The returned
     * {@link Result} always carries the {@link BalanceCheck} totals regardless of whether the draft
     * is valid.
     *
     * @param draft the draft voucher to validate (must not be {@code null})
     * @return a structured, exception-free result
     */
    public static Result validate(DraftVoucher draft) {
        List<String> violations = new ArrayList<>();

        // Req 5.7 — voucher date, type, and narration must be present.
        if (draft.date() == null) {
            violations.add("Voucher date is required.");
        }
        if (draft.type() == null) {
            violations.add("Voucher type is required.");
        }
        if (draft.narration() == null || draft.narration().isBlank()) {
            violations.add("Voucher narration is required.");
        }

        List<PostingLine> lines = draft.lines();

        // Req 5.1 — at least two voucher lines.
        if (lines.size() < 2) {
            violations.add("A voucher must contain at least two voucher lines (found " + lines.size() + ").");
        }

        // Reqs 5.4, 5.5, 5.6 — per-line ledger reference and a strictly positive amount.
        for (int i = 0; i < lines.size(); i++) {
            PostingLine line = lines.get(i);
            int number = i + 1;
            if (line == null) {
                violations.add("Voucher line " + number + " is missing.");
                continue;
            }
            if (!line.hasLedgerReference()) {
                violations.add("Voucher line " + number + " must reference a ledger account.");
            }
            if (!line.hasPositiveAmount()) {
                violations.add("Voucher line " + number + " amount must be greater than zero.");
            }
        }

        // Reqs 5.2, 5.3 — debit total must equal credit total; report exact totals on imbalance.
        BalanceCheck balance = totals(draft);
        if (!balance.balanced()) {
            violations.add("Voucher is not balanced: debit total "
                    + balance.debitTotal().toPlainString()
                    + " does not equal credit total "
                    + balance.creditTotal().toPlainString()
                    + " (difference "
                    + balance.difference().toPlainString() + ").");
        }

        return new Result(ValidationResult.from(violations), balance);
    }
}
