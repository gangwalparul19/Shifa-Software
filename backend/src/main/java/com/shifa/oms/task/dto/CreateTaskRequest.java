package com.shifa.oms.task.dto;

import com.shifa.oms.task.TaskPriority;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/**
 * Create-task request (CRM expansion Phase 2, Task 10; Req 8.1). A title is
 * required; description, due date/time, priority, and a lead/customer link are
 * optional. An admin/team-lead may set {@code ownerUserId} to create the task
 * for a team member; a salesperson's own id is used when absent.
 */
public record CreateTaskRequest(
        @NotBlank @Size(max = 200) String title,
        @Size(max = 1000) String description,
        Long leadId,
        @Size(max = 10) String customerMobile,
        LocalDateTime dueAt,
        TaskPriority priority,
        Long ownerUserId) {
}
