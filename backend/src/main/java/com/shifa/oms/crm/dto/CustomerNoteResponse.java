package com.shifa.oms.crm.dto;

import com.shifa.oms.crm.CustomerNote;

import java.time.LocalDateTime;

/**
 * A single staff note on a customer's timeline (FEATURE-ROADMAP §1.1).
 *
 * @param id         the note id
 * @param note       the note text
 * @param authorName the display name of the staff member who wrote it (nullable)
 * @param createdAt  when it was written
 */
public record CustomerNoteResponse(
        Long id,
        String note,
        String authorName,
        LocalDateTime createdAt
) {

    public static CustomerNoteResponse from(CustomerNote note) {
        return new CustomerNoteResponse(
                note.getId(),
                note.getNote(),
                note.getCreatedByName(),
                note.getCreatedAt());
    }
}
