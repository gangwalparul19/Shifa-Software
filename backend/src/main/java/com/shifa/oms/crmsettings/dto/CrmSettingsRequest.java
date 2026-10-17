package com.shifa.oms.crmsettings.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import java.time.LocalTime;

/** Update the single-row CRM settings (CRM expansion Phase 3, Req 18.3). */
public record CrmSettingsRequest(
        LocalTime followUpDefaultTime,
        @Min(1) @Max(720) Integer missedGraceHours,
        Long defaultPipelineId,
        String defaultAssignmentRule) {
}
