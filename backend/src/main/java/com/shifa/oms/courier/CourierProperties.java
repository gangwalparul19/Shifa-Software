package com.shifa.oms.courier;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Configuration for the courier integration, bound from {@code app.courier.*}
 * (design "Courier Integration"; base config in {@code application.yml}).
 *
 * @param mode              client backend: {@code MOCK} (local dev, default) or
 *                          {@code HTTP} (real Courier API, cloud)
 * @param baseUrl           the real Courier API base URL (HTTP mode)
 * @param apiKey            the API key for the real Courier API (HTTP mode)
 * @param webhookHmacSecret shared secret used to HMAC-validate inbound webhooks
 * @param requestTimeout    per-call timeout after which a courier call is treated
 *                          as a failure (Req 12.4)
 * @param maxAttempts       maximum assignment attempts before the outbox event is
 *                          marked FAILED and the admin is notified (Req 12.4)
 * @param retryBackoff      delay before the next assignment attempt after a failure
 * @param companyName       the default courier company used for assignments (seeded)
 */
@ConfigurationProperties(prefix = "app.courier")
public record CourierProperties(
        String mode,
        String baseUrl,
        String apiKey,
        String webhookHmacSecret,
        Duration requestTimeout,
        Integer maxAttempts,
        Duration retryBackoff,
        String companyName) {

    public CourierProperties {
        if (mode == null || mode.isBlank()) {
            mode = "MOCK";
        }
        if (requestTimeout == null) {
            requestTimeout = Duration.ofSeconds(10);
        }
        if (maxAttempts == null || maxAttempts < 1) {
            maxAttempts = 3;
        }
        if (retryBackoff == null) {
            retryBackoff = Duration.ofSeconds(30);
        }
        if (companyName == null || companyName.isBlank()) {
            companyName = "Shifa Express";
        }
    }

    public boolean isMock() {
        return "MOCK".equalsIgnoreCase(mode);
    }
}
