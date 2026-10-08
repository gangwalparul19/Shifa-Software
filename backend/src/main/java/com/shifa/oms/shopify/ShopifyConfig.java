package com.shifa.oms.shopify;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Enables {@link ShopifyProperties} binding for the Shopify order-import webhook.
 */
@Configuration
@EnableConfigurationProperties(ShopifyProperties.class)
public class ShopifyConfig {
}
