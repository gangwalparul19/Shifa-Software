package com.shifa.oms.reconciliation.dto;

import java.util.List;

/**
 * The result of a courier COD remittance CSV import (enhancement: "courier
 * remittance import & auto-match").
 *
 * <p>When {@code dryRun} was true this is a preview — the counts describe what
 * <em>would</em> happen and nothing was settled. When false, every row that
 * cleanly matched an unsettled COD receivable AND agreed on amount was settled
 * in this call; mismatches/unmatched rows are reported for manual follow-up
 * (never auto-settled on a guess).
 *
 * @param dryRun     whether this was a preview (nothing settled) or a commit
 * @param totalRows  total data rows processed (excludes the header)
 * @param settled    count of rows classified SETTLED
 * @param mismatched count of rows classified MISMATCH
 * @param notFound   count of rows classified ORDER_NOT_FOUND
 * @param noReceivable count of rows classified NO_RECEIVABLE
 * @param errors     count of rows classified ERROR
 * @param rows       per-row outcomes in file order
 */
public record RemittanceImportResponse(
        boolean dryRun,
        int totalRows,
        int settled,
        int mismatched,
        int notFound,
        int noReceivable,
        int errors,
        List<RemittanceRowResult> rows
) {

    /** Builds the response from the per-row results, deriving the counts. */
    public static RemittanceImportResponse of(boolean dryRun, List<RemittanceRowResult> rows) {
        int settled = 0;
        int mismatched = 0;
        int notFound = 0;
        int noReceivable = 0;
        int errors = 0;
        for (RemittanceRowResult r : rows) {
            switch (r.status()) {
                case SETTLED -> settled++;
                case MISMATCH -> mismatched++;
                case ORDER_NOT_FOUND -> notFound++;
                case NO_RECEIVABLE -> noReceivable++;
                case ERROR -> errors++;
            }
        }
        return new RemittanceImportResponse(
                dryRun, rows.size(), settled, mismatched, notFound, noReceivable, errors, rows);
    }
}
