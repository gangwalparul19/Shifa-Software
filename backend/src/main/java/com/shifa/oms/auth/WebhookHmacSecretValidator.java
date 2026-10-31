package com.shifa.oms.auth;

import com.shifa.oms.courier.CourierProperties;
import com.shifa.oms.shopify.ShopifyProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Fail-fast guard for inbound webhook HMAC secrets under the production profile.
 *
 * <p>Webhook endpoints are publicly reachable because their authentication is
 * the signature check itself. Production therefore must not boot with either
 * secret blank. Non-production profiles keep the explicit non-strict opt-out so
 * local/demo/test environments can exercise the endpoints without credentials.</p>
 */
@Component
@Profile("prod")
public class WebhookHmacSecretValidator {

    private static final Logger log = LoggerFactory.getLogger(WebhookHmacSecretValidator.class);

    private final ShopifyProperties shopifyProperties;
    private final CourierProperties courierProperties;

    @Autowired
    public WebhookHmacSecretValidator(ShopifyProperties shopifyProperties,
                                      CourierProperties courierProperties) {
        this.shopifyProperties = shopifyProperties;
        this.courierProperties = courierProperties;
    }

    @PostConstruct
    void validate() {
        requireConfigured("SHOPIFY_WEBHOOK_HMAC_SECRET", shopifyProperties.webhookHmacSecret());
        requireConfigured("COURIER_WEBHOOK_HMAC_SECRET", courierProperties.webhookHmacSecret());
        log.info("Inbound webhook HMAC secrets validated for production.");
    }

    private static void requireConfigured(String environmentVariable, String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "Inbound webhook HMAC secret is not configured. Set " + environmentVariable
                            + " before starting in production.");
        }
    }
}
