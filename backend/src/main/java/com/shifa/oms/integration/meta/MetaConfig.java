package com.shifa.oms.integration.meta;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * Binds {@code app.meta.*} into {@link MetaProperties} (spec {@code meta-lead-sync}).
 *
 * <p>Deliberately unconditional: the webhook endpoint and the event store must exist
 * even while {@code app.meta.enabled} is false, so a delivery is recorded for
 * inspection during setup rather than silently 404ing. The flag gates <em>lead
 * capture</em>, not receipt.
 *
 * <p>At startup it logs a warning naming each missing secret key (Req 9.3) — the
 * key names only, never the values (Req 9.4).
 */
@Configuration
@EnableConfigurationProperties(MetaProperties.class)
public class MetaConfig {

    private static final Logger log = LoggerFactory.getLogger(MetaConfig.class);

    private final MetaProperties properties;

    public MetaConfig(MetaProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    void warnOnMissingConfiguration() {
        List<String> missing = new ArrayList<>();
        if (!properties.hasAppSecret()) {
            missing.add("app.meta.app-secret");
        }
        if (!properties.hasPageAccessToken()) {
            missing.add("app.meta.page-access-token");
        }
        if (!properties.hasVerifyToken()) {
            missing.add("app.meta.verify-token");
        }
        if (!missing.isEmpty()) {
            log.warn("Meta lead sync is not fully configured — missing {}. "
                            + "Webhook deliveries will be stored but leads cannot be captured "
                            + "until these are set (app.meta.enabled={}).",
                    missing, properties.isEnabled());
        }
    }
}
