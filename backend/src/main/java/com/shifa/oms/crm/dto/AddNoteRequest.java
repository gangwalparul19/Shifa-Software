package com.shifa.oms.crm.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Payload to add a note to a customer's timeline
 * ({@code POST /api/admin/customers/{mobile}/notes}, FEATURE-ROADMAP §1.1).
 */
public record AddNoteRequest(
        @NotBlank(message = "note is required")
        @Size(max = 1000, message = "note must be at most 1000 characters")
        String note
) {
}
