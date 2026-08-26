package com.shifa.oms.ledger;

import com.shifa.oms.common.ResourceNotFoundException;
import com.shifa.oms.common.ValidationException;
import com.shifa.oms.ledger.domain.DrCr;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Objects;

/**
 * Records ledger-account opening balances per financial year and reports whether a financial year's
 * opening balances are balanced (General Ledger, Reqs 3.1, 3.3).
 *
 * <p>An opening balance is a strictly positive magnitude on a {@link DrCr} side; there is at most one
 * opening balance per {@code (ledger account, financial year)} (enforced by the unique constraint on
 * {@code opening_balances}). {@link #recordOpeningBalance} therefore <em>upserts</em>: it updates the
 * existing row for the pair when present, otherwise inserts a new one (Req 3.1).
 *
 * <p>{@link #checkBalance} sums the opening debits against the opening credits for a financial year
 * and reports whether they balance together with the signed difference {@code debitTotal -
 * creditTotal} (Req 3.3), mirroring the double-entry rule the Trial Balance depends on.
 *
 * <p>Money is held as {@link BigDecimal} at scale 2 ({@code HALF_UP}), matching the codebase-wide
 * convention; constructor injection and {@code @Transactional} follow the module's service style.
 */
@Service
@Transactional
public class OpeningBalanceService {

    /** Scale used for money amounts/totals, matching the codebase-wide {@code BigDecimal} scale-2 convention. */
    private static final int MONEY_SCALE = 2;

    private final OpeningBalanceRepository openingBalanceRepository;
    private final LedgerAccountRepository ledgerAccountRepository;
    private final FinancialYearRepository financialYearRepository;

    public OpeningBalanceService(OpeningBalanceRepository openingBalanceRepository,
                                 LedgerAccountRepository ledgerAccountRepository,
                                 FinancialYearRepository financialYearRepository) {
        this.openingBalanceRepository = openingBalanceRepository;
        this.ledgerAccountRepository = ledgerAccountRepository;
        this.financialYearRepository = financialYearRepository;
    }

    /**
     * Records (upserts) the opening balance of a ledger account for a financial year (Req 3.1).
     *
     * <p>Validates that the amount is strictly greater than zero and that the referenced ledger
     * account and financial year exist. When an opening balance already exists for the
     * {@code (ledger account, financial year)} pair, its amount and side are updated; otherwise a new
     * opening balance is created — respecting the unique {@code (ledger_account_id,
     * financial_year_id)} constraint.
     *
     * @param ledgerAccountId the ledger account the opening balance is for
     * @param financialYearId the financial year the opening balance applies to
     * @param amount          the opening balance magnitude; must be greater than zero
     * @param side            whether the opening balance is a {@code DEBIT} or {@code CREDIT}
     * @return the persisted (created or updated) opening balance
     * @throws ValidationException       if {@code amount} is null or not greater than zero, or {@code side} is null
     * @throws ResourceNotFoundException if the ledger account or financial year does not exist
     */
    public OpeningBalance recordOpeningBalance(Long ledgerAccountId, Long financialYearId, BigDecimal amount,
                                               DrCr side) {
        Objects.requireNonNull(ledgerAccountId, "ledgerAccountId");
        Objects.requireNonNull(financialYearId, "financialYearId");
        if (side == null) {
            throw new ValidationException("Opening balance side (DEBIT or CREDIT) is required.");
        }
        if (amount == null || amount.signum() <= 0) {
            throw new ValidationException("Opening balance amount must be greater than zero.");
        }
        if (!ledgerAccountRepository.existsById(ledgerAccountId)) {
            throw new ResourceNotFoundException("Ledger account " + ledgerAccountId + " does not exist.");
        }
        if (!financialYearRepository.existsById(financialYearId)) {
            throw new ResourceNotFoundException("Financial year " + financialYearId + " does not exist.");
        }

        BigDecimal normalisedAmount = amount.setScale(MONEY_SCALE, RoundingMode.HALF_UP);

        OpeningBalance opening = openingBalanceRepository
                .findByLedgerAccountIdAndFinancialYearId(ledgerAccountId, financialYearId)
                .orElse(null);
        if (opening == null) {
            opening = new OpeningBalance(ledgerAccountId, financialYearId, normalisedAmount, side);
        } else {
            opening.setAmount(normalisedAmount);
            opening.setSide(side);
        }
        return openingBalanceRepository.save(opening);
    }

    /**
     * Reports whether every opening balance recorded for a financial year balances, i.e. the total of
     * opening debits equals the total of opening credits (Req 3.3).
     *
     * @param financialYearId the financial year whose opening balances are checked
     * @return the debit total, credit total, signed difference ({@code debitTotal - creditTotal}), and
     *         whether the opening balances balance
     */
    @Transactional(readOnly = true)
    public OpeningBalanceCheck checkBalance(Long financialYearId) {
        Objects.requireNonNull(financialYearId, "financialYearId");
        List<OpeningBalance> openings = openingBalanceRepository.findByFinancialYearId(financialYearId);

        BigDecimal debitTotal = BigDecimal.ZERO;
        BigDecimal creditTotal = BigDecimal.ZERO;
        for (OpeningBalance opening : openings) {
            BigDecimal amount = opening.getAmount() == null ? BigDecimal.ZERO : opening.getAmount();
            if (opening.getSide() == DrCr.DEBIT) {
                debitTotal = debitTotal.add(amount);
            } else {
                creditTotal = creditTotal.add(amount);
            }
        }
        debitTotal = debitTotal.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        creditTotal = creditTotal.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        BigDecimal difference = debitTotal.subtract(creditTotal);
        boolean balanced = difference.signum() == 0;
        return new OpeningBalanceCheck(balanced, debitTotal, creditTotal, difference);
    }

    /**
     * The result of a financial year's opening-balance balancing check (Req 3.3).
     *
     * <p>When {@link #balanced} is {@code false}, {@link #difference} is the amount by which the
     * opening debits exceed the opening credits ({@code debitTotal - creditTotal}); a negative
     * difference means the opening credits exceed the opening debits.
     *
     * @param balanced    {@code true} when the opening debits equal the opening credits
     * @param debitTotal  the sum of all opening-balance debit amounts for the financial year
     * @param creditTotal the sum of all opening-balance credit amounts for the financial year
     * @param difference  {@code debitTotal - creditTotal} (zero when balanced)
     */
    public record OpeningBalanceCheck(boolean balanced, BigDecimal debitTotal, BigDecimal creditTotal,
                                      BigDecimal difference) {
        public OpeningBalanceCheck {
            Objects.requireNonNull(debitTotal, "debitTotal");
            Objects.requireNonNull(creditTotal, "creditTotal");
            Objects.requireNonNull(difference, "difference");
        }
    }
}
