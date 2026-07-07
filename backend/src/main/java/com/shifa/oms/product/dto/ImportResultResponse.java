package com.shifa.oms.product.dto;

import java.util.List;

/**
 * The result of a bulk product CSV import ("operations depth" Feature 2).
 *
 * <p>When {@code dryRun} was true this is a preview — the counts describe what
 * <em>would</em> happen and nothing was persisted. When false the valid rows
 * were applied in a single transaction and the counts describe the committed
 * outcome.
 *
 * @param dryRun        whether this was a preview (nothing persisted) or a commit
 * @param totalRows     total data rows processed (excludes the header)
 * @param created       count of rows classified CREATE
 * @param updated       count of rows classified UPDATE
 * @param skipped       count of rows classified SKIP
 * @param errors        count of rows classified ERROR
 * @param rows          per-row outcomes in file order
 */
public record ImportResultResponse(
        boolean dryRun,
        int totalRows,
        int created,
        int updated,
        int skipped,
        int errors,
        List<ImportRowResult> rows
) {

    /** Builds the response from the per-row results, deriving the counts. */
    public static ImportResultResponse of(boolean dryRun, List<ImportRowResult> rows) {
        int created = 0;
        int updated = 0;
        int skipped = 0;
        int errors = 0;
        for (ImportRowResult r : rows) {
            switch (r.action()) {
                case CREATE -> created++;
                case UPDATE -> updated++;
                case SKIP -> skipped++;
                case ERROR -> errors++;
            }
        }
        return new ImportResultResponse(dryRun, rows.size(), created, updated, skipped, errors, rows);
    }
}
