package com.shifa.oms.shopify;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the Shopify integration, bound from {@code app.shopify.*}.
 *
 * <p>The public storefront is on Shopify; a Shopify {@code orders/create} webhook
 * mirrors each order into this OMS. Shopify signs the raw webhook body with the
 * app's shared secret and sends the <em>base64</em> HMAC-SHA256 digest in the
 * {@code X-Shopify-Hmac-Sha256} header; {@link ShopifyHmacVerifier} recomputes it
 * and compares in constant time.
 *
 * @param webhookHmacSecret the shared secret used to verify inbound webhook
 *                          signatures. When blank (local dev default) verification
 *                          is skipped so the endpoint is usable without Shopify;
 *                          production MUST configure it.
 */
@ConfigurationProperties(prefix = "app.shopify")
public record ShopifyProperties(String webhookHmacSecret) {
}
