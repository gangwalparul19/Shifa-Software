package com.shifa.oms.lead.dto;

import com.shifa.oms.order.LeadSource;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * Lead-capture payload for {@code POST /api/leads} (Requirement 1, design
 * &sect;Components, &sect;API).
 *
 * <p>Requires a customer name and a {@link LeadSource} drawn from the defined set
 * (Req 1.1, 1.2). The mobile is optional but validated as a 10-digit number when
 * present (Req 1.3); email, note, an initial follow-up date, and an {@code OTHER}
 * source note (≤200 chars) are optional (Req 1.4, 1.2). Presence/membership and
 * the note-length bound are re-checked in {@code LeadService.capture} so the
 * specific 400 message can be returned even when bean-validation is bypassed.
 */
public record CreateLeadRequest(
        @NotBlank(message = "customerName is required")
        @Size(max = 120, message = "customerName must be at most 120 characters")
        String customerName,

        @NotNull(message = "leadSource is required")
        LeadSource leadSource,

        @Size(max = 200, message = "leadSourceNote must be at most 200 characters")
        String leadSourceNote,

        @Pattern(regexp = "\\d{10}", message = "customerMobile must be exactly 10 digits")
        String customerMobile,

        @Email(message = "customerEmail must be a valid email address")
        @Size(max = 150, message = "customerEmail must be at most 150 characters")
        String customerEmail,

        @Size(max = 1000, message = "note must be at most 1000 characters")
        String note,

        LocalDate followUpDate
) {
}
