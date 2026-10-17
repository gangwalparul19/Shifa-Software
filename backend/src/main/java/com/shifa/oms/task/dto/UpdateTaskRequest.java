package com.shifa.oms.task.dto;

import com.shifa.oms.task.TaskPriority;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/**
 * Update-task request (CRM expansion Phase 2, Task 10; Req 8.2). Edits the
 * mutable fields of an owned task; completion is a separate action
 * ({@code POST /api/tasks/{id}/complete}).
 */
public record UpdateTaskRequest(
        @NotBlank @Size(max = 200) String title,
        @Size(max = 1000) String description,
        Long leadId,
        @Size(max = 10) String customerMobile,
        LocalDateTime dueAt,
        TaskPriority priority) {
}
