package com.shifa.oms.lead;

import com.shifa.oms.order.LeadSource;

import java.time.LocalDate;

/**
 * A minimal, plain projection of a lead used by the pure
 * {@link LeadReportAggregator} (design &sect;Reporting). Carrying just the fields
 * the reports group on — origin channel, current status, lost reason, owner, and
 * the capture date — keeps the aggregator free of persistence concerns and lets
 * the conversion / grouping properties (design &sect;Correctness Properties (9))
 * be exercised directly over generated inputs.
 *
 * @param source      the lead's origin channel (never null for a persisted lead)
 * @param status      the lead's current {@link LeadStatus}
 * @param lostReason  the categorized reason (only when {@code status = LOST})
 * @param ownerUserId the owning salesperson/admin user id
 * @param createdDate the capture date, used for date-range windowing
 */
public record LeadReportRecord(
        LeadSource source,
        LeadStatus status,
        LostReason lostReason,
        Long ownerUserId,
        LocalDate createdDate) {

    /** Projects a persisted {@link LeadEntity} to its report record. */
    public static LeadReportRecord from(LeadEntity lead) {
        LocalDate created = lead.getCreatedAt() == null ? null : lead.getCreatedAt().toLocalDate();
        return new LeadReportRecord(
                lead.getLeadSource(), lead.getStatus(), lead.getLostReason(),
                lead.getOwnerUserId(), created);
    }
}
