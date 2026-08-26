package com.shifa.oms.ledger;

import com.shifa.oms.ledger.FinancialYearService.Period;
import com.shifa.oms.ledger.domain.VoucherType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Read-only Day Book service — a chronological listing of all posted vouchers in a reporting period
 * (Req 13).
 *
 * <p>Given a reporting period (a financial-year id <em>or</em> an explicit {@code from}/{@code to}
 * range, defaulting to the current financial year — resolved by the shared {@link ReportPeriodResolver},
 * Req 4.4) and an <em>optional</em> voucher-type filter, it returns every voucher whose voucher date
 * falls within the period, ordered chronologically (voucher date then id, Req 13.1), each row carrying
 * the voucher's reference, date, type, and narration together with its lines' debit/credit amounts
 * (Req 13.2). When a voucher type is supplied, only vouchers of that type are returned (Req 13.3).
 *
 * <p>The service takes the pure {@link VoucherType} directly for the optional filter, keeping its input
 * clean; the controller (task 10.x) parses a request string via {@link VoucherType#fromName(String)}.
 *
 * <p>Voucher lines are batch-loaded for the whole period in a single query
 * ({@link VoucherLineRepository#findByVoucherIdIn(java.util.Collection)}) and grouped in memory to
 * avoid an N+1 query per voucher.
 */
@Service
@Transactional(readOnly = true)
public class DayBookService {

    private final ReportPeriodResolver reportPeriodResolver;
    private final VoucherRepository voucherRepository;
    private final VoucherLineRepository voucherLineRepository;

    public DayBookService(ReportPeriodResolver reportPeriodResolver,
                          VoucherRepository voucherRepository,
                          VoucherLineRepository voucherLineRepository) {
        this.reportPeriodResolver = reportPeriodResolver;
        this.voucherRepository = voucherRepository;
        this.voucherLineRepository = voucherLineRepository;
    }

    /**
     * Builds the Day Book for a reporting period, optionally filtered to a single voucher type.
     *
     * @param financialYearId a financial year id, or {@code null}
     * @param from            the range start, or {@code null}
     * @param to              the range end, or {@code null}
     * @param voucherType     an optional voucher-type filter, or {@code null} for all types (Req 13.3)
     * @return the resolved period plus the in-period vouchers as chronological Day Book rows
     */
    public DayBook dayBook(Long financialYearId, LocalDate from, LocalDate to, VoucherType voucherType) {
        Period period = reportPeriodResolver.resolve(financialYearId, from, to);

        List<Voucher> vouchers = voucherType == null
                ? voucherRepository.findByVoucherDateBetweenOrderByVoucherDateAscIdAsc(period.from(), period.to())
                : voucherRepository.findByVoucherTypeAndVoucherDateBetweenOrderByVoucherDateAscIdAsc(
                        voucherType, period.from(), period.to());

        List<Long> voucherIds = vouchers.stream().map(Voucher::getId).toList();

        // Batch-load every in-period line in one query, then group by voucher to avoid N+1.
        Map<Long, List<VoucherLine>> linesByVoucher = voucherIds.isEmpty()
                ? Map.of()
                : voucherLineRepository.findByVoucherIdIn(voucherIds).stream()
                        .collect(Collectors.groupingBy(VoucherLine::getVoucherId));

        List<DayBookRow> rows = new ArrayList<>(vouchers.size());
        for (Voucher voucher : vouchers) {
            List<VoucherLine> voucherLines = linesByVoucher.getOrDefault(voucher.getId(), List.of());
            List<DayBookLine> lines = voucherLines.stream()
                    .sorted(Comparator.comparingInt(VoucherLine::getLineOrder)
                            .thenComparing(VoucherLine::getId))
                    .map(line -> new DayBookLine(line.getLedgerAccountId(), line.getDebit(), line.getCredit()))
                    .toList();
            rows.add(new DayBookRow(
                    voucher.getId(),
                    voucher.getVoucherReference(),
                    voucher.getVoucherDate(),
                    voucher.getVoucherType(),
                    voucher.getNarration(),
                    lines));
        }

        return new DayBook(period.from(), period.to(), period.financialYearId(), voucherType, rows);
    }

    /**
     * A single line of a Day Book voucher row: the ledger account posted to and its debit/credit
     * amounts (exactly one is non-null and positive, the other {@code null}; Req 13.2).
     *
     * @param ledgerAccountId the ledger account the line was posted to
     * @param debit           the debit amount, or {@code null} when this is a credit line
     * @param credit          the credit amount, or {@code null} when this is a debit line
     */
    public record DayBookLine(Long ledgerAccountId, BigDecimal debit, BigDecimal credit) {
    }

    /**
     * A Day Book row for one voucher: its identity/metadata plus the debit/credit amounts of its lines
     * (Req 13.2).
     *
     * @param voucherId the voucher's id
     * @param reference the per-type-per-FY voucher reference
     * @param date      the voucher date
     * @param type      the voucher type
     * @param narration the voucher narration
     * @param lines     the voucher's lines, in entry order
     */
    public record DayBookRow(Long voucherId,
                             String reference,
                             LocalDate date,
                             VoucherType type,
                             String narration,
                             List<DayBookLine> lines) {
    }

    /**
     * The cohesive Day Book result (Req 13.1): the resolved reporting period, the applied optional
     * voucher-type filter, and the in-period vouchers as chronological rows.
     *
     * @param from            the inclusive period start
     * @param to              the inclusive period end
     * @param financialYearId the financial year the period was resolved from, or {@code null} for a range
     * @param voucherType     the applied voucher-type filter, or {@code null} for all types
     * @param rows            the in-period vouchers, ordered by voucher date then id
     */
    public record DayBook(LocalDate from,
                          LocalDate to,
                          Long financialYearId,
                          VoucherType voucherType,
                          List<DayBookRow> rows) {
    }
}
