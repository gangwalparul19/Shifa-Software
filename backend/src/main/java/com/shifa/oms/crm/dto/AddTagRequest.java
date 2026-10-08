package com.shifa.oms.crm.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Payload to attach a segment tag to a customer
 * ({@code POST /api/admin/customers/{mobile}/tags}, FEATURE-ROADMAP §1.4).
 */
public record AddTagRequest(
        @NotBlank(message = "tag is required")
        @Size(max = 40, message = "tag must be at most 40 characters")
        String tag
) {
}
