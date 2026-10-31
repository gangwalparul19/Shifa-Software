package com.shifa.oms.auth;

import com.shifa.oms.courier.CourierProperties;
import com.shifa.oms.shopify.ShopifyProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebhookHmacSecretValidatorTest {

    private static final String SHOPIFY_SECRET = "shopify-production-webhook-secret";
    private static final String COURIER_SECRET = "courier-production-webhook-secret";

    private CourierProperties courier(String secret) {
        return new CourierProperties("MOCK", null, null, secret,
                Duration.ofSeconds(10), 3, Duration.ofSeconds(30), "Shifa Express");
    }

    private WebhookHmacSecretValidator validator(String shopify, String courier) {
        return new WebhookHmacSecretValidator(new ShopifyProperties(shopify), courier(courier));
    }

    @Test
    void acceptsBothConfiguredSecrets() {
        assertThatCode(() -> validator(SHOPIFY_SECRET, COURIER_SECRET).validate())
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsBlankShopifySecret() {
        assertThatThrownBy(() -> validator("  ", COURIER_SECRET).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SHOPIFY_WEBHOOK_HMAC_SECRET");
    }

    @Test
    void rejectsBlankCourierSecret() {
        assertThatThrownBy(() -> validator(SHOPIFY_SECRET, null).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("COURIER_WEBHOOK_HMAC_SECRET");
    }
}
