package com.shifa.oms.lead.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-lead result of a bulk reassignment (CRM expansion, Req 1.4): the ids that
 * were reassigned and the ones skipped with a reason (e.g. not visible / not
 * found).
 */
public record BulkAssignResult(List<Long> succeeded, List<Skipped> skipped) {

    /** One skipped lead with the reason it could not be reassigned. */
    public record Skipped(Long id, String reason) {
    }

    /** A mutable accumulator the service fills while iterating the selected ids. */
    public static final class Builder {
        private final List<Long> succeeded = new ArrayList<>();
        private final List<Skipped> skipped = new ArrayList<>();

        public void succeeded(Long id) {
            succeeded.add(id);
        }

        public void skipped(Long id, String reason) {
            skipped.add(new Skipped(id, reason));
        }

        public BulkAssignResult build() {
            return new BulkAssignResult(List.copyOf(succeeded), List.copyOf(skipped));
        }
    }
}
