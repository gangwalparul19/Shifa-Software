package com.shifa.oms.whatsapp.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Payload to create or update a WhatsApp message template
 * ({@code POST/PUT /api/whatsapp-templates}). The {@code body} may contain
 * {@code {placeholder}} tokens; validation only guards length + presence.
 *
 * @param title     button label (required)
 * @param body      message body with optional {placeholder} tokens (required)
 * @param icon      optional Tabler icon name (defaults server-side)
 * @param active    whether the template is offered to senders
 * @param sortOrder display order (ascending); optional, defaults to 0
 */
public record WhatsappTemplateRequest(
        @NotBlank(message = "title is required")
        @Size(max = 120, message = "title must be at most 120 characters")
        String title,

        @NotBlank(message = "body is required")
        @Size(max = 2000, message = "body must be at most 2000 characters")
        String body,

        @Size(max = 40, message = "icon must be at most 40 characters")
        String icon,

        Boolean active,

        Integer sortOrder
) {
}
