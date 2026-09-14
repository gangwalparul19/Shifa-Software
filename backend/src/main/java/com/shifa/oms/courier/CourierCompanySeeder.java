package com.shifa.oms.courier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Seeds a demo courier company on startup so courier assignment works end to end
 * in local development. Runs only under the {@code local} profile and is
 * idempotent — it creates the company only when none with the configured name
 * exists.
 *
 * <p>No carrier {@code tracking_url_template} is set: we do not track with any
 * individual carrier directly — QuikShipX is the aggregator and tracking is via
 * their track-order API (surfaced in-app), so no carrier-specific link is built.
 */
@Component
@Profile("local")
@Order(30)
public class CourierCompanySeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(CourierCompanySeeder.class);
    private static final String TRACKING_TEMPLATE = null;

    private final CourierCompanyRepository courierCompanyRepository;
    private final CourierProperties properties;

    public CourierCompanySeeder(CourierCompanyRepository courierCompanyRepository,
                                CourierProperties properties) {
        this.courierCompanyRepository = courierCompanyRepository;
        this.properties = properties;
    }

    @Override
    public void run(String... args) {
        String name = properties.companyName();
        if (courierCompanyRepository.findFirstByName(name).isPresent()) {
            return;
        }
        courierCompanyRepository.save(new CourierCompany(name, TRACKING_TEMPLATE));
        log.info("Seeded demo courier company '{}' (local profile).", name);
    }
}
