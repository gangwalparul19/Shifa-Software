package com.shifa.oms.notification;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Configuration for the WhatsApp notification service, bound from
 * {@code app.whatsapp.*} (design "Notification Service"; base config in
 * {@code application.yml}).
 *
 * @param mode           client backend: {@code MOCK} (local dev, default) or
 *                       {@code HTTP} (real Meta Cloud API, cloud)
 * @param baseUrl        the Meta Cloud API base URL (HTTP mode)
 * @param phoneNumberId  the WhatsApp phone number id (HTTP mode)
 * @param accessToken    the Meta access token (HTTP mode)
 * @param maxAttempts    maximum send attempts before the outbox event is marked
 *                       FAILED and the order flagged for admin review (Req 14.4)
 * @param retryBackoff   delay before the next send attempt after a failure
 */
@ConfigurationProperties(prefix = "app.whatsapp")
public record WhatsAppProperties(
        String mode,
        String baseUrl,
        String phoneNumberId,
        String accessToken,
        Integer maxAttempts,
        Duration retryBackoff) {

    public WhatsAppProperties {
        if (mode == null || mode.isBlank()) {
            mode = "MOCK";
        }
        if (maxAttempts == null || maxAttempts < 1) {
            maxAttempts = 3;
        }
        if (retryBackoff == null) {
            retryBackoff = Duration.ofSeconds(30);
        }
    }

    public boolean isMock() {
        return "MOCK".equalsIgnoreCase(mode);
    }
}
