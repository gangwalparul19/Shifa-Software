package com.shifa.oms.lead.dto;

import com.shifa.oms.lead.LeadEntity;
import com.shifa.oms.lead.LeadStatus;
import com.shifa.oms.order.LeadSource;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Compact lead projection returned by the list / pipeline / due-follow-ups
 * endpoints (Req 3.3, 3.4, 5.2, design &sect;API). Omits the status history and
 * long note for a lightweight, mobile-friendly result list.
 */
public record LeadSummaryResponse(
        Long id,
        String customerName,
        String customerMobile,
        LeadSource leadSource,
        LeadStatus status,
        LocalDate followUpDate,
        Long ownerUserId,
        Long convertedOrderId,
        LocalDateTime createdAt
) {

    public static LeadSummaryResponse from(LeadEntity lead) {
        return new LeadSummaryResponse(
                lead.getId(),
                lead.getCustomerName(),
                lead.getCustomerMobile(),
                lead.getLeadSource(),
                lead.getStatus(),
                lead.getFollowUpDate(),
                lead.getOwnerUserId(),
                lead.getConvertedOrderId(),
                lead.getCreatedAt());
    }
}
