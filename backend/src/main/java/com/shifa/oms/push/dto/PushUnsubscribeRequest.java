package com.shifa.oms.push.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request to remove a Web Push subscription by its endpoint
 * ({@code POST /api/notifications/push/unsubscribe}, FEATURE-ROADMAP §8.3).
 */
public record PushUnsubscribeRequest(
        @NotBlank(message = "endpoint is required")
        String endpoint
) {
}
