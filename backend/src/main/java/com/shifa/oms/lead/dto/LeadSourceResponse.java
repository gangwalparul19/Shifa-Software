package com.shifa.oms.lead.dto;

import com.shifa.oms.lead.ManagedLeadSource;

/** A managed lead-source availability entry (CRM expansion Phase 3, Req 18.2). */
public record LeadSourceResponse(Long id, String name, boolean active, int sortOrder) {

    public static LeadSourceResponse from(ManagedLeadSource source) {
        return new LeadSourceResponse(source.getId(), source.getName(),
                source.isActive(), source.getSortOrder());
    }
}
