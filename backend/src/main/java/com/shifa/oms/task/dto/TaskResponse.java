package com.shifa.oms.task.dto;

import com.shifa.oms.task.TaskEntity;
import com.shifa.oms.task.TaskPriority;
import com.shifa.oms.task.TaskStatus;

import java.time.LocalDateTime;

/** Read-model projection of a {@link TaskEntity} (CRM expansion Phase 2, Task 10). */
public record TaskResponse(
        Long id,
        Long ownerUserId,
        String title,
        String description,
        Long leadId,
        String customerMobile,
        LocalDateTime dueAt,
        TaskPriority priority,
        TaskStatus status,
        LocalDateTime completedAt,
        String completionNote,
        String cancelReason,
        LocalDateTime cancelledAt,
        LocalDateTime createdAt,
        /** The owner's display name, filled in for list views; null when unknown. */
        String ownerName) {

    public static TaskResponse from(TaskEntity t) {
        return new TaskResponse(
                t.getId(), t.getOwnerUserId(), t.getTitle(), t.getDescription(),
                t.getLeadId(), t.getCustomerMobile(), t.getDueAt(), t.getPriority(),
                t.getStatus(), t.getCompletedAt(), t.getCompletionNote(),
                t.getCancelReason(), t.getCancelledAt(), t.getCreatedAt(), null);
    }

    /** Copy with the owner's display name set. */
    public TaskResponse withOwnerName(String name) {
        return new TaskResponse(id, ownerUserId, title, description, leadId, customerMobile,
                dueAt, priority, status, completedAt, completionNote, cancelReason,
                cancelledAt, createdAt, name);
    }
}
