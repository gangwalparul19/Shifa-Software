package com.shifa.oms.ledger.dto;

import com.shifa.oms.ledger.domain.DrCr;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/**
 * Create/upsert payload for a ledger-account opening balance
 * ({@code POST /api/accounting/opening-balances}, Req 3.1).
 *
 * <p>An opening balance is a strictly positive magnitude posted on exactly one {@link DrCr} side for
 * a {@code (ledger account, financial year)} pair; {@code OpeningBalanceService.recordOpeningBalance}
 * upserts the single row allowed per pair. All four fields are required — the ledger account and
 * financial year the balance is for, the positive amount, and its debit-or-credit side.
 *
 * @param ledgerAccountId the ledger account the opening balance is for (required, must exist)
 * @param financialYearId the financial year the opening balance applies to (required, must exist)
 * @param amount          the opening balance magnitude (required, strictly greater than zero)
 * @param side            whether the opening balance is a {@code DEBIT} or {@code CREDIT} (required)
 */
public record OpeningBalanceRequest(
        @NotNull(message = "ledgerAccountId is required")
        Long ledgerAccountId,

        @NotNull(message = "financialYearId is required")
        Long financialYearId,

        @NotNull(message = "amount is required")
        @Positive(message = "amount must be greater than zero")
        BigDecimal amount,

        @NotNull(message = "side (DEBIT or CREDIT) is required")
        DrCr side
) {
}
