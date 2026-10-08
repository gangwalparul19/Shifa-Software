package com.shifa.oms.lead.dto;

import com.shifa.oms.lead.LeadEntity;
import com.shifa.oms.lead.LeadStatus;
import com.shifa.oms.lead.LeadStatusHistory;
import com.shifa.oms.lead.LostReason;
import com.shifa.oms.order.LeadSource;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Full lead detail returned by {@code POST /api/leads} and
 * {@code GET /api/leads/{id}} (Req 3.5, design &sect;API). Carries the capture
 * fields, current status + lost reason, follow-up date, owner, the linked order
 * (when WON), and the ordered status history.
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
        Long ownerUserId,
        Long convertedOrderId,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
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

    public static LeadResponse from(LeadEntity lead) {
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
                lead.getOwnerUserId(),
                lead.getConvertedOrderId(),
                lead.getCreatedAt(),
                lead.getUpdatedAt(),
                history);
    }
}
