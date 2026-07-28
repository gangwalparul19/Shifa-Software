package com.shifa.oms.whatsapp.dto;

import com.shifa.oms.whatsapp.WhatsappTemplate;

import java.time.LocalDateTime;

/**
 * A customizable WhatsApp message template (V44).
 *
 * @param id            the template id
 * @param key           the stable slug key
 * @param title         the button label shown to senders
 * @param body          the message body with {placeholder} tokens
 * @param icon          a Tabler icon name for the quick-message button
 * @param active        whether it is offered to senders
 * @param sortOrder     display order (ascending)
 * @param createdByName who created it (nullable)
 * @param createdAt     when it was created
 * @param updatedAt     when it was last edited (nullable)
 */
public record WhatsappTemplateResponse(
        Long id,
        String key,
        String title,
        String body,
        String icon,
        boolean active,
        int sortOrder,
        String createdByName,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {

    public static WhatsappTemplateResponse from(WhatsappTemplate t) {
        return new WhatsappTemplateResponse(
                t.getId(), t.getTemplateKey(), t.getTitle(), t.getBody(), t.getIcon(),
                t.isActive(), t.getSortOrder(), t.getCreatedByName(),
                t.getCreatedAt(), t.getUpdatedAt());
    }
}
