package com.shifa.oms.quikshipx;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link QuikShipXProperties} so the QuikShipX integration binds from
 * {@code app.quikshipx.*}. Mirrors the per-module properties-registration pattern
 * ({@code CourierConfig}, {@code NotificationConfig}). Scheduling is already
 * enabled application-wide, so the {@code @Scheduled} drainer needs no extra
 * annotation here.
 */
@Configuration
@EnableConfigurationProperties(QuikShipXProperties.class)
public class QuikShipXConfig {
}
