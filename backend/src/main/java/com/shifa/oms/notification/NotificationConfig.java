package com.shifa.oms.notification;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Wiring for the notification module: enables {@link WhatsAppProperties} binding
 * from {@code app.whatsapp.*}. The concrete {@link WhatsAppClient} is selected by
 * the {@code app.whatsapp.mode} property ({@link MockWhatsAppClient} by default).
 */
@Configuration
@EnableConfigurationProperties(WhatsAppProperties.class)
public class NotificationConfig {
}
