package com.shifa.oms.integration.meta;

import com.shifa.oms.integration.meta.MetaLeadIngestService.ReceiveResult;
import com.shifa.oms.integration.meta.dto.MetaConnectivityResponse;
import com.shifa.oms.integration.meta.dto.MetaSimulateResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * Admin connectivity/access test for the Meta integration (spec {@code meta-lead-sync},
 * Req 10). Lets an administrator verify the configured Page id + access token against
 * the Graph API before relying on live webhooks.
 *
 * <p>Standard authenticated {@code /api/**} route, restricted to ADMIN. The access
 * token is never returned (Req 10.4).
 */
@RestController
@RequestMapping("/api/admin/integrations/meta")
@PreAuthorize("hasRole('ADMIN')")
public class MetaConnectivityController {

    private static final Logger log = LoggerFactory.getLogger(MetaConnectivityController.class);

    private final MetaGraphClient graphClient;
    private final MetaProperties properties;
    private final MetaLeadIngestService ingestService;
    private final MetaLeadIngestDrainer drainer;

    public MetaConnectivityController(MetaGraphClient graphClient,
                                      MetaProperties properties,
                                      MetaLeadIngestService ingestService,
                                      MetaLeadIngestDrainer drainer) {
        this.graphClient = graphClient;
        this.properties = properties;
        this.ingestService = ingestService;
        this.drainer = drainer;
    }

    /**
     * Calls the Graph API for the configured Page using the configured token and
     * returns the page name on success, or the Graph error description on failure
     * (never the token). Always 200 — the {@code ok} flag carries the verdict so the
     * admin UI can show a clear pass/fail without treating a bad token as a server error.
     */
    @GetMapping("/health")
    public MetaConnectivityResponse health() {
        String pageId = properties.resolvedPageId();
        try {
            String pageName = graphClient.fetchPageName();
            return MetaConnectivityResponse.success(pageId, pageName);
        } catch (MetaGraphException e) {
            log.warn("Meta connectivity test failed for page {}: {}", pageId, e.getMessage());
            return MetaConnectivityResponse.failure(pageId, e.getMessage());
        }
    }

    /**
     * Pushes a synthetic {@code leadgen} notification through the real ingest pipeline
     * (store &rarr; enqueue &rarr; drain &rarr; fetch &rarr; map &rarr; capture) so the
     * end-to-end flow can be demonstrated locally without a live Meta webhook. In MOCK
     * mode the lead data comes from {@link MockMetaGraphClient}; the captured lead
     * appears in the Leads pipeline owned by the {@code meta-leads} system user.
     *
     * <p>ADMIN-only utility. Requires {@code app.meta.enabled=true} to capture (a
     * disabled integration stores the delivery but does not create a lead).
     */
    @PostMapping("/simulate-lead")
    public MetaSimulateResponse simulateLead() {
        String leadgenId = "SIMULATED-" + System.currentTimeMillis();
        String payload = "{\"object\":\"page\",\"entry\":[{\"id\":\"" + properties.resolvedPageId()
                + "\",\"time\":" + Instant.now().getEpochSecond()
                + ",\"changes\":[{\"field\":\"leadgen\",\"value\":{\"leadgen_id\":\"" + leadgenId
                + "\",\"form_id\":\"SIM-FORM\",\"page_id\":\"" + properties.resolvedPageId()
                + "\"}}]}]}";

        ReceiveResult received = ingestService.receive(payload.getBytes(StandardCharsets.UTF_8));
        int processed = drainer.drainIngestions();
        String message = properties.isEnabled()
                ? "Simulated Meta lead captured — check the Leads pipeline (owner: meta-leads)."
                : "Stored but NOT captured: app.meta.enabled is false. Set META_ENABLED=true to capture.";
        log.info("Simulated Meta lead {}: queued={}, processed={}", leadgenId, received.queued(), processed);
        return new MetaSimulateResponse(leadgenId, received.queued(), processed,
                properties.isMockMode(), message);
    }
}
