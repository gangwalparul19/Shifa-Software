package com.shifa.oms.crmsettings.dto;

import com.shifa.oms.crmsettings.CrmSettings;

import java.time.LocalTime;

/** The single-row CRM settings (CRM expansion Phase 3, Req 18.3). */
public record CrmSettingsResponse(LocalTime followUpDefaultTime, int missedGraceHours,
                                  Long defaultPipelineId, String defaultAssignmentRule) {

    public static CrmSettingsResponse from(CrmSettings settings) {
        return new CrmSettingsResponse(settings.getFollowUpDefaultTime(),
                settings.getMissedGraceHours(), settings.getDefaultPipelineId(),
                settings.getDefaultAssignmentRule());
    }
}
