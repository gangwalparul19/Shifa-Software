package com.shifa.oms.activity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/activities/log} (CRM expansion Phase 2,
 * Task 9) — the frontend logs a lightweight CALL / WHATSAPP (or other) touch on
 * a lead/customer initiated from the CRM (Req 11.1). At least one of
 * {@code leadId} / {@code customerMobile} should be supplied; the actor is the
 * caller.
 */
public record LogActivityRequest(
        @NotBlank @Size(max = 40) String type,
        Long leadId,
        @Size(max = 10) String customerMobile,
        @NotBlank @Size(max = 500) String summary,
        @Size(max = 1000) String detail) {
}
