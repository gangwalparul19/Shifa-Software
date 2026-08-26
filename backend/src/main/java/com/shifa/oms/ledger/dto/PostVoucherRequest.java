package com.shifa.oms.ledger.dto;

import com.shifa.oms.ledger.VoucherService.PostVoucherCommand;
import com.shifa.oms.ledger.VoucherService.VoucherLineCommand;
import com.shifa.oms.ledger.domain.DrCr;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Post payload for a manual double-entry voucher ({@code POST /api/accounting/vouchers}, Reqs 5.1–5.7,
 * 7.1–7.3).
 *
 * <p>Bean validation enforces the always-required voucher header — a voucher type name, a voucher
 * date, and a narration (Req 5.7) — and that at least one line is supplied (Req 5.1; the ≥ 2 lines
 * and the debit == credit balancing rules are enforced by the pure double-entry validator in
 * {@code VoucherService}, which can report exact totals per Req 5.3). {@code type} is carried as a
 * <em>string</em> and parsed strictly by the service ({@code VoucherType.fromName}) so an unsupported
 * name is rejected with a validation error (Req 7.3) rather than a binding failure.
 *
 * <p>Each {@link Line} references exactly one ledger account, carries a strictly positive amount, and
 * names its {@link DrCr} side; the "exactly one of debit/credit" invariant is expressed here by a
 * single required {@code side} + {@code amount} pair, matching the {@code VoucherLineCommand} the
 * service consumes.
 *
 * @param type      the voucher-type name (required; e.g. {@code "JOURNAL"}), parsed strictly by the service
 * @param date      the voucher date (required, Req 5.7)
 * @param narration the voucher narration (required, Req 5.7)
 * @param lines     the ordered Dr/Cr lines (required, at least one; balancing enforced by the service)
 */
public record PostVoucherRequest(
        @NotBlank(message = "type is required")
        String type,

        @NotNull(message = "date is required")
        LocalDate date,

        @NotBlank(message = "narration is required")
        String narration,

        @NotEmpty(message = "at least one voucher line is required")
        @Valid
        List<Line> lines
) {

    /**
     * One Dr/Cr line of a {@link PostVoucherRequest}: the ledger account to post against, the side,
     * and the strictly positive amount (Reqs 5.4, 5.6).
     *
     * @param ledgerAccountId the ledger account this line posts against (required)
     * @param side            the debit or credit side (required)
     * @param amount          the posting amount (required, strictly greater than zero)
     * @param narration       an optional per-line note
     */
    public record Line(
            @NotNull(message = "ledgerAccountId is required")
            Long ledgerAccountId,

            @NotNull(message = "side (DEBIT or CREDIT) is required")
            DrCr side,

            @NotNull(message = "amount is required")
            @Positive(message = "amount must be greater than zero")
            BigDecimal amount,

            @Size(max = 500, message = "narration must be at most 500 characters")
            String narration
    ) {
    }

    /**
     * Adapts this request to the {@link PostVoucherCommand} the {@code VoucherService} consumes,
     * preserving line order. Validation of the type name, balancing, and per-line rules remains the
     * service's responsibility.
     *
     * @return the equivalent {@link PostVoucherCommand}
     */
    public PostVoucherCommand toCommand() {
        List<Line> source = lines == null ? List.of() : lines;
        List<VoucherLineCommand> commandLines = source.stream()
                .map(line -> new VoucherLineCommand(line.ledgerAccountId(), line.side(), line.amount()))
                .toList();
        return new PostVoucherCommand(type, date, narration, commandLines);
    }
}
