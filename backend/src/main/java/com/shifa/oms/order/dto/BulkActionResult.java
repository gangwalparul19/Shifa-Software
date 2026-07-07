package com.shifa.oms.order.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-id result of a bulk order action (bulk-approve / bulk-mark-packed),
 * enabling the admin UI to report partial success (ROADMAP 2.2 "Wave 2").
 *
 * <p>Every requested id lands in exactly one of the two lists: {@link #succeeded}
 * for ids the action was applied to, or {@link #skipped} for ids that were not
 * eligible (unknown id, wrong lifecycle state, or a rejected transition), each
 * carrying a human-readable {@code reason}.
 *
 * @param succeeded ids the action was successfully applied to
 * @param skipped   ids that were skipped, each with the reason it was skipped
 */
public record BulkActionResult(
        List<Long> succeeded,
        List<SkippedItem> skipped) {

    /**
     * A single skipped id and why it was skipped.
     *
     * @param id     the order id that was skipped
     * @param reason the human-readable reason it was not processed
     */
    public record SkippedItem(Long id, String reason) {
    }

    /** A mutable accumulator used while iterating the requested ids. */
    public static final class Builder {
        private final List<Long> succeeded = new ArrayList<>();
        private final List<SkippedItem> skipped = new ArrayList<>();

        public void succeeded(Long id) {
            succeeded.add(id);
        }

        public void skipped(Long id, String reason) {
            skipped.add(new SkippedItem(id, reason));
        }

        public BulkActionResult build() {
            return new BulkActionResult(List.copyOf(succeeded), List.copyOf(skipped));
        }
    }
}
