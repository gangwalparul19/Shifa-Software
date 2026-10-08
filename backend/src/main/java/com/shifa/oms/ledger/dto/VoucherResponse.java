package com.shifa.oms.ledger.dto;

import com.shifa.oms.ledger.Voucher;
import com.shifa.oms.ledger.VoucherLine;
import com.shifa.oms.ledger.domain.VoucherType;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

/**
 * Read view of a posted voucher and its lines ({@code POST /api/accounting/vouchers},
 * {@code POST /api/accounting/vouchers/{id}/reverse}, {@code GET /api/accounting/vouchers/{id}},
 * Reqs 5, 6, 7).
 *
 * <p>A posted voucher is immutable; this view exposes its identity/metadata, the source-document key
 * for an auto-posted voucher, and the bidirectional reversal links (Req 6.4). {@link #reversesVoucherId}
 * is set on a reversing voucher, {@link #reversedByVoucherId} on an original that has been reversed.
 * The {@link VoucherType} is serialised as its {@code name()}; money is at scale 2.
 *
 * @param id                  the voucher id
 * @param type                the voucher type
 * @param date                the voucher date
 * @param financialYearId     the financial year the voucher was posted into
 * @param reference           the per-type-per-FY unique voucher reference
 * @param narration           the voucher narration
 * @param postedAt            when the voucher was posted
 * @param postedBy            who posted the voucher
 * @param sourceType          the source-document type for an auto-posted voucher, or {@code null}
 * @param sourceId            the source-document id for an auto-posted voucher, or {@code null}
 * @param reversesVoucherId   the original voucher id when this is a reversing voucher, or {@code null}
 * @param reversedByVoucherId the reversing voucher id when this voucher has been reversed, or {@code null}
 * @param lines               the voucher's Dr/Cr lines, in entry order
 */
public record VoucherResponse(
        Long id,
        VoucherType type,
        LocalDate date,
        Long financialYearId,
        String reference,
        String narration,
        LocalDateTime postedAt,
        String postedBy,
        String sourceType,
        Long sourceId,
        Long reversesVoucherId,
        Long reversedByVoucherId,
        List<Line> lines
) {

    private static final int MONEY_SCALE = 2;

    /**
     * Maps a posted {@link Voucher} plus its persisted {@link VoucherLine}s to a response view; lines
     * are ordered by their {@code lineOrder}.
     *
     * @param voucher the posted voucher
     * @param lines   the voucher's lines (any order; sorted here by line order)
     */
    public static VoucherResponse from(Voucher voucher, List<VoucherLine> lines) {
        List<Line> lineViews = (lines == null ? List.<VoucherLine>of() : lines).stream()
                .sorted(Comparator.comparingInt(VoucherLine::getLineOrder)
                        .thenComparing(line -> line.getId() == null ? Long.MAX_VALUE : line.getId()))
                .map(Line::from)
                .toList();
        return new VoucherResponse(
                voucher.getId(),
                voucher.getVoucherType(),
                voucher.getVoucherDate(),
                voucher.getFinancialYearId(),
                voucher.getVoucherReference(),
                voucher.getNarration(),
                voucher.getPostedAt(),
                voucher.getPostedBy(),
                voucher.getSourceType(),
                voucher.getSourceId(),
                voucher.getReversesVoucherId(),
                voucher.getReversedByVoucherId(),
                lineViews);
    }

    private static BigDecimal scale(BigDecimal value) {
        return value == null ? null : value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * One Dr/Cr line of a voucher (Reqs 5.4–5.6): the ledger account posted to and its debit/credit
     * amounts — exactly one is non-null and positive, the other {@code null}.
     *
     * @param ledgerAccountId the ledger account the line was posted to
     * @param debit           the debit amount (scale 2), or {@code null} when this is a credit line
     * @param credit          the credit amount (scale 2), or {@code null} when this is a debit line
     * @param lineOrder       the entry order of the line within the voucher
     * @param narration       an optional per-line note
     */
    public record Line(
            Long ledgerAccountId,
            BigDecimal debit,
            BigDecimal credit,
            int lineOrder,
            String narration
    ) {

        /** Maps a persisted {@link VoucherLine} to its response view (amounts at scale 2). */
        public static Line from(VoucherLine line) {
            return new Line(
                    line.getLedgerAccountId(),
                    scale(line.getDebit()),
                    scale(line.getCredit()),
                    line.getLineOrder(),
                    line.getLineNarration());
        }
    }
}
