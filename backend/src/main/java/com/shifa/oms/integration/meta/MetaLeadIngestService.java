package com.shifa.oms.integration.meta;

import com.shifa.oms.common.ValidationException;
import com.shifa.oms.integration.IntegrationEvent;
import com.shifa.oms.integration.IntegrationEventStore;
import com.shifa.oms.integration.IntegrationOutcome;
import com.shifa.oms.integration.IntegrationSource;
import com.shifa.oms.integration.MalformedPayloadException;
import com.shifa.oms.integration.meta.dto.MetaLeadData;
import com.shifa.oms.integration.meta.dto.MetaLeadEntry;
import com.shifa.oms.lead.LeadService;
import com.shifa.oms.lead.dto.CreateLeadRequest;
import com.shifa.oms.lead.dto.LeadResponse;
import com.shifa.oms.platform.outbox.OutboxEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Ingestion of Meta Lead Ads notifications into the {@code leads} pipeline (spec
 * {@code meta-lead-sync}, Req 3, 4, 5, 6, 7, 11), mirroring the Shopify ingestor.
 *
 * <p>Two entry points:
 * <ul>
 *   <li>{@link #receive(byte[])} — called on the webhook request thread inside one
 *       transaction: store each {@code leadgen} entry in {@code integration_events}
 *       (source META, idempotent on {@code leadgen_id}) and enqueue an outbox event.
 *       Fast and side-effect-light so Meta gets its 200 well within 5 seconds.</li>
 *   <li>{@link #ingest(Long)} — called off-thread by the drainer: fetch the lead
 *       from the Graph API, map it, and capture it as the Meta Leads system user.
 *       The Graph call is I/O, so this is NOT wrapped in a single DB transaction;
 *       each sub-step manages its own.</li>
 * </ul>
 */
@Service
public class MetaLeadIngestService {

    private static final Logger log = LoggerFactory.getLogger(MetaLeadIngestService.class);

    private final IntegrationEventStore eventStore;
    private final OutboxEventPublisher outboxEventPublisher;
    private final MetaGraphClient graphClient;
    private final LeadService leadService;
    private final MetaSystemActorProvider actorProvider;
    private final MetaProperties properties;

    public MetaLeadIngestService(IntegrationEventStore eventStore,
                                 OutboxEventPublisher outboxEventPublisher,
                                 MetaGraphClient graphClient,
                                 LeadService leadService,
                                 MetaSystemActorProvider actorProvider,
                                 MetaProperties properties) {
        this.eventStore = eventStore;
        this.outboxEventPublisher = outboxEventPublisher;
        this.graphClient = graphClient;
        this.leadService = leadService;
        this.actorProvider = actorProvider;
        this.properties = properties;
    }

    // --- Receipt (webhook thread) ------------------------------------------

    /**
     * Records every {@code leadgen} entry in a verified notification and enqueues its
     * ingestion, all in one transaction (Req 3.1, 3.3, 4). A repeat delivery of an
     * already-stored {@code leadgen_id} is a no-op (Req 4.2). An unparseable body is
     * accepted (logged, counted as ignored) rather than 500'd — retrying a permanently
     * bad body would loop forever.
     *
     * @param rawBody the exact received notification bytes
     * @return a summary of what was queued vs. skipped
     */
    @Transactional
    public ReceiveResult receive(byte[] rawBody) {
        String payload = new String(rawBody == null ? new byte[0] : rawBody, StandardCharsets.UTF_8);
        List<MetaLeadEntry> entries;
        try {
            entries = MetaLeadNotificationCodec.parse(payload);
        } catch (MalformedPayloadException malformed) {
            log.warn("Meta webhook body could not be parsed; acknowledging without processing: {}",
                    malformed.getMessage());
            return new ReceiveResult(0, 0, 0);
        }

        int queued = 0;
        int duplicates = 0;
        for (MetaLeadEntry entry : entries) {
            var stored = eventStore.record(IntegrationSource.META, entry.leadgenId(), "leadgen", payload);
            if (stored.isEmpty()) {
                duplicates++;
                log.info("Meta lead {} was already received; acknowledging without reprocessing",
                        entry.leadgenId());
                continue;
            }
            outboxEventPublisher.publishMetaLeadIngest(stored.get().getId(), entry.leadgenId());
            queued++;
        }
        return new ReceiveResult(entries.size(), queued, duplicates);
    }

    // --- Ingestion (drainer thread) ----------------------------------------

    /**
     * Fetches, maps and captures a single stored Meta lead (Req 5, 6, 7). Records the
     * terminal outcome on the {@code integration_events} row (the created lead id on
     * success). Never throws for a classified failure — returns an {@link IngestResult}
     * the drainer uses to decide retry vs. fail.
     */
    public IngestResult ingest(Long integrationEventId) {
        IntegrationEvent event = eventStore.find(integrationEventId).orElse(null);
        if (event == null) {
            return new IngestResult(Status.SKIPPED, "integration event " + integrationEventId + " not found");
        }
        if (event.getOutcome() == IntegrationOutcome.PROCESSED) {
            return new IngestResult(Status.SKIPPED, "already processed");
        }
        String leadgenId = event.getExternalEventId();

        if (!properties.isEnabled()) {
            // Deliveries are stored while disabled but not captured; settle so the
            // drainer stops polling. Re-enabling + replaying will capture it.
            log.info("Meta lead sync disabled (app.meta.enabled=false); storing lead {} without capture",
                    leadgenId);
            return new IngestResult(Status.SKIPPED, "meta lead sync disabled");
        }

        // (1) Fetch — transient vs. auth failure (Req 5.3, 5.4).
        MetaLeadData data;
        try {
            data = graphClient.fetchLead(leadgenId);
        } catch (MetaGraphException e) {
            if (e.isRetryable()) {
                return new IngestResult(Status.RETRYABLE_FAILURE, e.getMessage());
            }
            eventStore.markOutcome(integrationEventId, IntegrationOutcome.PROCESSING_FAILED, e.getMessage());
            return new IngestResult(Status.PERMANENT_FAILURE, e.getMessage());
        }

        // (2) Map + (3) capture as the Meta Leads system user (Req 6, 7).
        CreateLeadRequest request = MetaLeadFieldMapper.map(data.fields(), data.formName());
        try {
            LeadResponse lead = leadService.capture(request, actorProvider.systemActor());
            eventStore.markOutcome(integrationEventId, IntegrationOutcome.PROCESSED, null, lead.id());
            log.info("Captured Meta lead {} as lead {} (owner=meta-leads)", leadgenId, lead.id());
            return new IngestResult(Status.CAPTURED, "lead " + lead.id());
        } catch (ValidationException | IllegalStateException permanent) {
            // Bad mapped data or a missing system user fails identically on retry.
            eventStore.markOutcome(integrationEventId, IntegrationOutcome.PROCESSING_FAILED,
                    permanent.getMessage());
            return new IngestResult(Status.PERMANENT_FAILURE, permanent.getMessage());
        } catch (RuntimeException transientFault) {
            // An unexpected fault (e.g. transient DB issue) — worth retrying.
            String detail = transientFault.getMessage() == null
                    ? transientFault.getClass().getSimpleName() : transientFault.getMessage();
            return new IngestResult(Status.RETRYABLE_FAILURE, detail);
        }
    }

    /** Outcome of {@link #receive(byte[])}. */
    public record ReceiveResult(int received, int queued, int duplicates) {
    }

    /** Outcome of {@link #ingest(Long)}; the drainer maps it to a retry decision. */
    public record IngestResult(Status status, String detail) {

        public boolean isRetryable() {
            return status == Status.RETRYABLE_FAILURE;
        }

        public boolean isPermanentFailure() {
            return status == Status.PERMANENT_FAILURE;
        }
    }

    /** The classified result of an ingestion attempt. */
    public enum Status {
        /** A lead was captured. */
        CAPTURED,
        /** A settled no-op: duplicate, already processed, or feature disabled. */
        SKIPPED,
        /** A transient failure; retry with backoff. */
        RETRYABLE_FAILURE,
        /** A permanent failure (bad token, capture rejection); do not retry. */
        PERMANENT_FAILURE
    }
}
