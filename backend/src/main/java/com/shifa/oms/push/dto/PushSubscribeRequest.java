package com.shifa.oms.push.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * A browser Push API subscription posted by the client
 * ({@code POST /api/notifications/push/subscribe}, FEATURE-ROADMAP §8.3). Mirrors
 * the JSON shape of {@code PushSubscription.toJSON()}.
 */
public record PushSubscribeRequest(
        @NotBlank(message = "endpoint is required")
        String endpoint,

        @NotNull(message = "keys are required")
        @Valid
        Keys keys
) {

    /** The client's encryption keys from the browser subscription. */
    public record Keys(
            @NotBlank(message = "p256dh is required")
            String p256dh,

            @NotBlank(message = "auth is required")
            String auth
    ) {
    }
}
