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
        LocalDateTime createdAt) {

    public static TaskResponse from(TaskEntity t) {
        return new TaskResponse(
                t.getId(), t.getOwnerUserId(), t.getTitle(), t.getDescription(),
                t.getLeadId(), t.getCustomerMobile(), t.getDueAt(), t.getPriority(),
                t.getStatus(), t.getCompletedAt(), t.getCreatedAt());
    }
}
