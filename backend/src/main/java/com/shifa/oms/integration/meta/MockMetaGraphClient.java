package com.shifa.oms.integration.meta;

import com.shifa.oms.integration.meta.dto.MetaField;
import com.shifa.oms.integration.meta.dto.MetaLeadData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Offline {@link MetaGraphClient} for local development and demos (spec
 * {@code meta-lead-sync}). Active by default and whenever {@code app.meta.mode} is
 * not {@code HTTP}, so the full ingest pipeline (webhook &rarr; store &rarr; drain
 * &rarr; capture &rarr; dashboard) can be exercised with no Meta token and no
 * network — mirroring the MOCK courier/QuikShipX clients.
 *
 * <p>Returns deterministic-but-varied lead data derived from the {@code leadgen_id}
 * so simulated leads look realistic in the Leads pipeline.
 */
@Component
@ConditionalOnProperty(name = "app.meta.mode", havingValue = "MOCK", matchIfMissing = true)
public class MockMetaGraphClient implements MetaGraphClient {

    private static final Logger log = LoggerFactory.getLogger(MockMetaGraphClient.class);

    private static final String[] NAMES = {
            "Aarav Sharma", "Diya Patel", "Vivaan Reddy", "Ananya Iyer",
            "Kabir Nair", "Ishaan Gupta", "Meera Joshi", "Rohan Mehta"};
    private static final String[] CITIES = {
            "Mumbai", "Pune", "Bengaluru", "Hyderabad", "Delhi", "Chennai", "Jaipur", "Kolkata"};
    private static final String[] PRODUCTS = {
            "Ashwagandha", "Shilajit", "Triphala", "Chyawanprash", "Brahmi", "Giloy"};

    @Override
    public MetaLeadData fetchLead(String leadgenId) {
        int seed = Math.abs((leadgenId == null ? "0" : leadgenId).hashCode());
        String name = NAMES[seed % NAMES.length];
        String city = CITIES[(seed / 7) % CITIES.length];
        String product = PRODUCTS[(seed / 13) % PRODUCTS.length];
        String phone = "+9199" + String.format("%08d", seed % 100_000_000);
        String email = name.toLowerCase().replace(' ', '.') + "@example.com";

        log.info("[MOCK Meta] returning simulated lead for leadgen_id={} ({} from {})",
                leadgenId, name, city);
        return new MetaLeadData(leadgenId, "Shifa Lead Ad (MOCK)", List.of(
                new MetaField("full_name", name),
                new MetaField("email", email),
                new MetaField("phone_number", phone),
                new MetaField("city", city),
                new MetaField("which_product", product)));
    }

    @Override
    public String fetchPageName() {
        return "Shifa Herbal Remedies (MOCK)";
    }
}
