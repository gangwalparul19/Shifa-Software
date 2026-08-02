package com.shifa.oms.integration.quikshipx;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Binds {@code app.quikshipx.*} into {@link QuikShipXProperties}.
 *
 * <p>The client implementation itself is chosen by {@code @ConditionalOnProperty} on
 * {@link MockQuikShipXClient} ({@code mode=MOCK}, {@code matchIfMissing = true}) and
 * {@link HttpQuikShipXClient} ({@code mode=HTTP}), so exactly one
 * {@link QuikShipXClient} bean is ever active — the pattern already proven by the three
 * {@code StorageService} implementations.
 */
@Configuration
@EnableConfigurationProperties(QuikShipXProperties.class)
public class QuikShipXConfig {
}
