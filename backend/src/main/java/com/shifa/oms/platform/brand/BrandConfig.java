package com.shifa.oms.platform.brand;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link BrandProperties} so {@code app.brand.*} is bound and
 * injectable wherever the white-label brand name / order-code prefix is needed.
 */
@Configuration
@EnableConfigurationProperties(BrandProperties.class)
public class BrandConfig {
}
