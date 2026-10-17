package com.shifa.oms.lead.dto;

import com.shifa.oms.lead.LeadEntity;
import com.shifa.oms.lead.LeadStatus;
import com.shifa.oms.lead.LeadStatusHistory;
import com.shifa.oms.lead.LostReason;
import com.shifa.oms.order.LeadSource;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

/**
 * Full lead detail returned by {@code POST /api/leads} and
 * {@code GET /api/leads/{id}} (Req 3.5, design &sect;API). Carries the capture
 * fields, current status + lost reason, follow-up date/time, creator/assignee,
 * the admin-created marker, the linked order (when WON), the applied tags, and
 * the ordered status history.
 */
public record LeadResponse(
        Long id,
        String customerName,
        String customerMobile,
        String customerEmail,
        LeadSource leadSource,
        String leadSourceNote,
        LeadStatus status,
        LostReason lostReason,
        String lostReasonNote,
        String note,
        LocalDate followUpDate,
        LocalTime followUpTime,
        Long ownerUserId,
        Long createdByUserId,
        boolean createdByAdmin,
        LocalDateTime assignedAt,
        String addressLine,
        String city,
        String state,
        String postalCode,
        Long convertedOrderId,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        List<LeadTagResponse> tags,
        List<StatusHistoryEntry> statusHistory
) {

    /** One status-history row (from/to/actor/changed-at) for the detail trail. */
    public record StatusHistoryEntry(
            LeadStatus fromStatus,
            LeadStatus toStatus,
            String actor,
            LocalDateTime changedAt
    ) {
        public static StatusHistoryEntry from(LeadStatusHistory row) {
            return new StatusHistoryEntry(
                    row.getFromStatus(), row.getToStatus(), row.getActor(), row.getChangedAt());
        }
    }

    /** Full detail with no tags loaded (back-compat for callers without a tag view). */
    public static LeadResponse from(LeadEntity lead) {
        return from(lead, List.of());
    }

    /** Full detail including the lead's applied tags (CRM expansion, Req 4). */
    public static LeadResponse from(LeadEntity lead, List<LeadTagResponse> tags) {
        List<StatusHistoryEntry> history = lead.getStatusHistory().stream()
                .map(StatusHistoryEntry::from)
                .toList();
        return new LeadResponse(
                lead.getId(),
                lead.getCustomerName(),
                lead.getCustomerMobile(),
                lead.getCustomerEmail(),
                lead.getLeadSource(),
                lead.getLeadSourceNote(),
                lead.getStatus(),
                lead.getLostReason(),
                lead.getLostReasonNote(),
                lead.getNote(),
                lead.getFollowUpDate(),
                lead.getFollowUpTime(),
                lead.getOwnerUserId(),
                lead.getCreatedByUserId(),
                lead.isCreatedByAdmin(),
                lead.getAssignedAt(),
                lead.getAddressLine(),
                lead.getCity(),
                lead.getState(),
                lead.getPostalCode(),
                lead.getConvertedOrderId(),
                lead.getCreatedAt(),
                lead.getUpdatedAt(),
                tags == null ? List.of() : tags,
                history);
    }
}
