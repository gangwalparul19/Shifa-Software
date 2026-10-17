package com.shifa.oms.lead.dto;

import com.shifa.oms.lead.LeadEntity;
import com.shifa.oms.lead.LeadStatus;
import com.shifa.oms.order.LeadSource;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

/**
 * Compact lead projection returned by the list / pipeline / due-follow-ups
 * endpoints (Req 3.3, 3.4, 5.2, design &sect;API). Omits the status history and
 * long note for a lightweight, mobile-friendly result list; carries the
 * admin-created marker, follow-up time, assigned-at, and a lightweight list of
 * tag names (CRM expansion, Req 3, 4, 6).
 */
public record LeadSummaryResponse(
        Long id,
        String customerName,
        String customerMobile,
        LeadSource leadSource,
        LeadStatus status,
        LocalDate followUpDate,
        LocalTime followUpTime,
        Long ownerUserId,
        boolean createdByAdmin,
        LocalDateTime assignedAt,
        Long convertedOrderId,
        LocalDateTime createdAt,
        List<String> tags
) {

    /** Compact projection with no tags (back-compat for callers without a tag view). */
    public static LeadSummaryResponse from(LeadEntity lead) {
        return from(lead, List.of());
    }

    /** Compact projection including the lead's tag names (CRM expansion, Req 4). */
    public static LeadSummaryResponse from(LeadEntity lead, List<String> tagNames) {
        return new LeadSummaryResponse(
                lead.getId(),
                lead.getCustomerName(),
                lead.getCustomerMobile(),
                lead.getLeadSource(),
                lead.getStatus(),
                lead.getFollowUpDate(),
                lead.getFollowUpTime(),
                lead.getOwnerUserId(),
                lead.isCreatedByAdmin(),
                lead.getAssignedAt(),
                lead.getConvertedOrderId(),
                lead.getCreatedAt(),
                tagNames == null ? List.of() : tagNames);
    }
}
