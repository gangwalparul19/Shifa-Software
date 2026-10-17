package com.shifa.oms.task.dto;

import jakarta.validation.constraints.Size;

/**
 * Cancel-task request (CRM task close-out enhancement; Req 8.2). Carries an
 * OPTIONAL reason — the body may be absent/empty, in which case the task is
 * simply marked CANCELLED with no reason.
 */
public record CancelTaskRequest(
        @Size(max = 1000) String reason) {
}
