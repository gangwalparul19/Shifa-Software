package com.shifa.oms.task.dto;

import jakarta.validation.constraints.Size;

/**
 * Complete-task request (CRM task close-out enhancement; Req 8.2). Carries an
 * OPTIONAL completion note — the body may be absent/empty, in which case the
 * task is simply marked DONE with no note (backward compatible).
 */
public record CompleteTaskRequest(
        @Size(max = 1000) String note) {
}
