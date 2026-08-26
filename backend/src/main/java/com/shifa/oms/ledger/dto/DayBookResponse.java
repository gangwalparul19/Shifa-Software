package com.shifa.oms.ledger.dto;

import com.shifa.oms.ledger.DayBookService.DayBook;
import com.shifa.oms.ledger.DayBookService.DayBookLine;
import com.shifa.oms.ledger.DayBookService.DayBookRow;
import com.shifa.oms.ledger.domain.VoucherType;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

/**
 * Read view of the Day Book — a chronological listing of all posted vouchers in a reporting period
 * ({@code GET /api/accounting/vouchers}, Req 13).
 *
 * <p>Carries the resolved period, the applied optional voucher-type filter, and one {@link Row} per
 * in-period voucher (ordered by voucher date then id, Req 13.1), each row carrying its reference,
 * date, type, narration, and its lines' debit/credit amounts (Req 13.2). Voucher types are serialised
 * as their {@code name()}; money is at scale 2.
 *
 * @param from            the inclusive period start
 * @param to              the inclusive period end
 * @param financialYearId the financial year the period was resolved from, or {@code null} for a range
 * @param voucherType     the applied voucher-type filter, or {@code null} for all types (Req 13.3)
 * @param rows            the in-period vouchers, ordered chronologically
 */
public record DayBookResponse(
        LocalDate from,
        LocalDate to,
        Long financialYearId,
        VoucherType voucherType,
        List<Row> rows
) {

    private static final int MONEY_SCALE = 2;

    /** Maps a {@link DayBook} service result to its response view. */
    public static DayBookResponse from(DayBook dayBook) {
        List<Row> rows = dayBook.rows().stream().map(Row::from).toList();
        return new DayBookResponse(
                dayBook.from(),
                dayBook.to(),
                dayBook.financialYearId(),
                dayBook.voucherType(),
                rows);
    }

    private static BigDecimal scale(BigDecimal value) {
        return value == null ? null : value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * A Day Book row for one voucher (Req 13.2): its identity/metadata plus the debit/credit amounts
     * of its lines.
     *
     * @param voucherId the voucher id
     * @param reference the per-type-per-FY voucher reference
     * @param date      the voucher date
     * @param type      the voucher type
     * @param narration the voucher narration
     * @param lines     the voucher's lines, in entry order
     */
    public record Row(
            Long voucherId,
            String reference,
            LocalDate date,
            VoucherType type,
            String narration,
            List<Line> lines
    ) {

        /** Maps a {@link DayBookRow} to its response view. */
        public static Row from(DayBookRow row) {
            return new Row(
                    row.voucherId(),
                    row.reference(),
                    row.date(),
                    row.type(),
                    row.narration(),
                    row.lines().stream().map(Line::from).toList());
        }
    }

    /**
     * One line of a Day Book voucher row (Req 13.2): the ledger account posted to and its debit/credit
     * amounts (exactly one is non-null and positive).
     *
     * @param ledgerAccountId the ledger account the line was posted to
     * @param debit           the debit amount (scale 2), or {@code null} when this is a credit line
     * @param credit          the credit amount (scale 2), or {@code null} when this is a debit line
     */
    public record Line(
            Long ledgerAccountId,
            BigDecimal debit,
            BigDecimal credit
    ) {

        /** Maps a {@link DayBookLine} to its response view (amounts at scale 2). */
        public static Line from(DayBookLine line) {
            return new Line(line.ledgerAccountId(), scale(line.debit()), scale(line.credit()));
        }
    }
}
