package com.shifa.oms.dashboard;

import com.shifa.oms.platform.outbox.OutboxEvent;
import com.shifa.oms.platform.outbox.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Relays persisted admin-notification outbox rows to connected admins over SSE,
 * and periodically pushes live stats + activity counts (Req 11.2, 13.3, 17.4,
 * 19.5, 19.6).
 *
 * <p><strong>Post-commit delivery.</strong> Notifications are written to the
 * {@code outbox} table in the <em>same</em> transaction as the domain change
 * that produced them (packing scan, courier status change, claim filing, courier
 * assignment failure, WhatsApp failure). This scheduled relay reads only
 * <em>committed</em> {@code PENDING} rows, so it is inherently post-commit &mdash;
 * the dashboard never shows a change that was later rolled back.
 *
 * <p><strong>Nothing lost when no admin is connected (Req 11.2).</strong> The
 * relay only marks a row {@code SENT} after it has actually been broadcast, and
 * it only broadcasts while {@link AdminSseBroker#hasActiveEmitters()} is true.
 * If no admin is connected, the rows stay {@code PENDING} and are relayed as
 * soon as an admin connects.
 *
 * <p>The relay handles exactly the admin-notification event types (packed,
 * status-changed, claim-required, courier-assign-failed, whatsapp-failed, and
 * backup-failed); the integration event types ({@code COURIER_ASSIGN},
 * {@code WHATSAPP_NOTIFY}) are owned by their own drainers (tasks 14/15) and are
 * never touched here.
 */
@Component
public class OutboxSseRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxSseRelay.class);

    /** The outbox event types surfaced to admins over SSE (mapped 1:1 to SSE event names). */
    static final Set<String> ADMIN_NOTIFICATION_TYPES = Set.of(
            OutboxEvent.EVENT_ORDER_PACKED,
            OutboxEvent.EVENT_ORDER_STATUS_CHANGED,
            OutboxEvent.EVENT_CLAIM_FILED_REQUIRED,
            OutboxEvent.EVENT_COURIER_ASSIGN_FAILED,
            OutboxEvent.EVENT_WHATSAPP_FAILED,
            OutboxEvent.EVENT_BACKUP_FAILED,
            OutboxEvent.EVENT_LOW_STOCK);

    private final OutboxEventRepository outboxEventRepository;
    private final AdminSseBroker broker;
    private final DashboardMetricsService metricsService;

    public OutboxSseRelay(OutboxEventRepository outboxEventRepository,
                          AdminSseBroker broker,
                          DashboardMetricsService metricsService) {
        this.outboxEventRepository = outboxEventRepository;
        this.broker = broker;
        this.metricsService = metricsService;
    }

    /**
     * Relays pending admin-notification rows to connected admins, marking each
     * {@code SENT} once broadcast (Req 11.2, 13.3, 17.4). Skips entirely when no
     * admin is connected, leaving the rows {@code PENDING} so nothing is lost.
     *
     * @return the number of events relayed (for tests/diagnostics)
     */
    @Scheduled(fixedDelayString = "${app.dashboard.sse.relay-interval-ms:5000}")
    @Transactional
    public int relayPending() {
        if (!broker.hasActiveEmitters()) {
            return 0;
        }
        int relayed = 0;
        for (OutboxEvent event : outboxEventRepository
                .findByStatusOrderByCreatedAtAsc(OutboxEvent.STATUS_PENDING)) {
            if (!ADMIN_NOTIFICATION_TYPES.contains(event.getEventType())) {
                continue;
            }
            broker.broadcast(event.getEventType(), toSseData(event));
            event.markSent();
            outboxEventRepository.save(event);
            relayed++;
        }
        if (relayed > 0) {
            log.debug("Relayed {} admin notification(s) over SSE", relayed);
        }
        return relayed;
    }

    /**
     * Pushes the current live stats (Req 19.5) and activity counts (Req 19.6) to
     * connected admins so the dashboard's real-time numbers stay fresh. Skips
     * when no admin is connected.
     */
    @Scheduled(fixedDelayString = "${app.dashboard.sse.stats-interval-ms:15000}")
    public void pushLiveStats() {
        if (!broker.hasActiveEmitters()) {
            return;
        }
        broker.broadcast("LIVE_STATS", metricsService.liveStats());
        broker.broadcast("ACTIVITY", metricsService.activityCards());
    }

    /** Builds the SSE payload for a relayed event: its id/type plus its stored payload. */
    private static Map<String, Object> toSseData(OutboxEvent event) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("eventId", event.getId());
        data.put("type", event.getEventType());
        data.put("aggregateId", event.getAggregateId());
        if (event.getPayload() != null) {
            data.putAll(event.getPayload());
        }
        return data;
    }
}
