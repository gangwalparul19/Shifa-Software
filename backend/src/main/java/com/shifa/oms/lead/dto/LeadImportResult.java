package com.shifa.oms.lead.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * Aggregate result of a bulk lead upload (CRM expansion, Req 2): how many leads
 * were created and the rows skipped with a reason, so valid rows are created
 * even when some rows are invalid (Req 2.2).
 */
public record LeadImportResult(int created, List<Skipped> skipped) {

    /** One skipped row (1-based row number as it appeared in the file) + the reason. */
    public record Skipped(int row, String reason) {
    }

    /** A mutable accumulator the import service fills while iterating rows. */
    public static final class Builder {
        private int created;
        private final List<Skipped> skipped = new ArrayList<>();

        public void created() {
            created++;
        }

        public void skipped(int row, String reason) {
            skipped.add(new Skipped(row, reason));
        }

        public LeadImportResult build() {
            return new LeadImportResult(created, List.copyOf(skipped));
        }
    }
}
