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
 * <p>The seeded company uses a {@code tracking_url_template} of
 * {@code https://track.example.com/{awb}} so the customer tracking view (Req 13.4)
 * can build a link from any assigned AWB.
 */
@Component
@Profile("local")
@Order(30)
public class CourierCompanySeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(CourierCompanySeeder.class);
    private static final String TRACKING_TEMPLATE = "https://track.example.com/{awb}";

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
