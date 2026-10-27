package com.shifa.oms.reconciliation.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * The result of a courier COD remittance CSV import (enhancement: "courier
 * remittance import & auto-match"; extended in V99 for partial settlement +
 * persisted batches).
 *
 * <p>When {@code dryRun} was true this is a preview — the counts describe what
 * <em>would</em> happen and nothing was settled ({@code batchId} is null). When
 * false, every row that cleanly matched an unsettled COD receivable was fully or
 * partially settled in this call and the committed batch is persisted
 * ({@code batchId} is set); over-paid mismatches/unmatched rows are reported for
 * manual follow-up (never auto-settled on a guess).
 *
 * @param dryRun          whether this was a preview (nothing settled) or a commit
 * @param batchId         the persisted {@link com.shifa.oms.reconciliation.RemittanceBatch}
 *                        id on a commit, or {@code null} on a dry run
 * @param totalRows       total data rows processed (excludes the header)
 * @param settled         count of rows classified SETTLED (full)
 * @param partiallySettled count of rows classified PARTIALLY_SETTLED (short, settled with a shortfall)
 * @param mismatched      count of rows classified MISMATCH (over-paid beyond tolerance)
 * @param alreadySettled  count of rows classified ALREADY_SETTLED
 * @param notFound        count of rows classified ORDER_NOT_FOUND
 * @param noReceivable    count of rows classified NO_RECEIVABLE
 * @param errors          count of rows classified ERROR
 * @param totalRemitted   sum of remitted amounts across all rows
 * @param totalSettled    sum of amounts actually settled (full + partial)
 * @param totalShortfall  sum of shortfalls across partially-settled rows
 * @param rows            per-row outcomes in file order
 */
public record RemittanceImportResponse(
        boolean dryRun,
        Long batchId,
        int totalRows,
        int settled,
        int partiallySettled,
        int mismatched,
        int alreadySettled,
        int notFound,
        int noReceivable,
        int errors,
        BigDecimal totalRemitted,
        BigDecimal totalSettled,
        BigDecimal totalShortfall,
        List<RemittanceRowResult> rows
) {

    /** Builds a dry-run (no batch) response from the per-row results, deriving the counts. */
    public static RemittanceImportResponse of(boolean dryRun, List<RemittanceRowResult> rows) {
        return of(dryRun, null, rows);
    }

    /** Builds the response from the per-row results, deriving the counts + money totals. */
    public static RemittanceImportResponse of(boolean dryRun, Long batchId, List<RemittanceRowResult> rows) {
        int settled = 0;
        int partiallySettled = 0;
        int mismatched = 0;
        int alreadySettled = 0;
        int notFound = 0;
        int noReceivable = 0;
        int errors = 0;
        BigDecimal totalRemitted = BigDecimal.ZERO;
        BigDecimal totalSettled = BigDecimal.ZERO;
        BigDecimal totalShortfall = BigDecimal.ZERO;
        for (RemittanceRowResult r : rows) {
            switch (r.status()) {
                case SETTLED -> settled++;
                case PARTIALLY_SETTLED -> partiallySettled++;
                case MISMATCH -> mismatched++;
                case ALREADY_SETTLED -> alreadySettled++;
                case ORDER_NOT_FOUND -> notFound++;
                case NO_RECEIVABLE -> noReceivable++;
                case ERROR -> errors++;
            }
            totalRemitted = totalRemitted.add(nz(r.remittedAmount()));
            totalSettled = totalSettled.add(nz(r.settledAmount()));
            totalShortfall = totalShortfall.add(nz(r.shortfallAmount()));
        }
        return new RemittanceImportResponse(
                dryRun, batchId, rows.size(), settled, partiallySettled, mismatched, alreadySettled,
                notFound, noReceivable, errors,
                scale(totalRemitted), scale(totalSettled), scale(totalShortfall), rows);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static BigDecimal scale(BigDecimal v) {
        return v.setScale(2, java.math.RoundingMode.HALF_UP);
    }
}
