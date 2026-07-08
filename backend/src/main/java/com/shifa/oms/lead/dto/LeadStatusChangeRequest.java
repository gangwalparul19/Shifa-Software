package com.shifa.oms.lead.dto;

import com.shifa.oms.lead.LeadStatus;
import com.shifa.oms.lead.LostReason;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Advance-status payload for {@code POST /api/leads/{id}/status} (Requirement 2,
 * design &sect;API, &sect;State Machine).
 *
 * <p>Carries the requested {@code toStatus}; a {@link LostReason} is required
 * when {@code toStatus = LOST} (Req 2.3) with an optional free-text note
 * (meaningful for {@link LostReason#OTHER}). {@link LeadStatus#WON} is rejected
 * here — it is reachable only via Convert (Req 2.5). Legality and the LOST-reason
 * requirement are enforced in {@code LeadService.transition}.
 */
public record LeadStatusChangeRequest(
        @NotNull(message = "toStatus is required")
        LeadStatus toStatus,

        LostReason lostReason,

        @Size(max = 200, message = "lostReasonNote must be at most 200 characters")
        String lostReasonNote
) {
}
