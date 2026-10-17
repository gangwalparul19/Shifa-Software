package com.shifa.oms.lead.dto;

import com.shifa.oms.lead.LeadTag;

/**
 * A managed Lead_Tag definition returned by {@code GET /api/leads/tags} and
 * embedded in {@link LeadResponse} (CRM expansion, Req 4).
 */
public record LeadTagResponse(Long id, String name, String color, String kind,
                              boolean active, Long stageId) {

    public static LeadTagResponse from(LeadTag tag) {
        return new LeadTagResponse(tag.getId(), tag.getName(), tag.getColor(), tag.getKind(),
                tag.isActive(), tag.getStageId());
    }
}
