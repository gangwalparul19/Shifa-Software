package com.shifa.oms.courier;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Wiring for the courier module: enables {@link CourierProperties} binding from
 * {@code app.courier.*}. The concrete {@link CourierClient} is selected by the
 * {@code app.courier.mode} property ({@link MockCourierClient} by default).
 */
@Configuration
@EnableConfigurationProperties(CourierProperties.class)
public class CourierConfig {
}
