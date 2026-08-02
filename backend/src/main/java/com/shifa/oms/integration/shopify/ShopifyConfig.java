package com.shifa.oms.integration.shopify;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Binds {@code app.shopify.*} into {@link ShopifyProperties}.
 *
 * <p>Deliberately unconditional: the webhook endpoint and the event store must exist even
 * while {@code app.shopify.enabled} is false, so a delivery is recorded for inspection
 * during setup rather than silently 404ing. The flag gates <em>order creation</em>, not
 * receipt (Req 15.7).
 */
@Configuration
@EnableConfigurationProperties(ShopifyProperties.class)
public class ShopifyConfig {
}
