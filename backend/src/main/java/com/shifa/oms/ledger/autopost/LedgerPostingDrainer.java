package com.shifa.oms.ledger.autopost;

import com.shifa.oms.adminnotification.AdminNotification;
import com.shifa.oms.adminnotification.StaffNotificationDispatcher;
import com.shifa.oms.auth.Role;
import com.shifa.oms.ledger.Voucher;
import com.shifa.oms.ledger.VoucherService;
import com.shifa.oms.ledger.domain.DraftVoucher;
import com.shifa.oms.platform.outbox.OutboxEvent;
import com.shifa.oms.platform.outbox.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Scheduled drainer for {@link OutboxEvent#EVENT_LEDGER_POST LEDGER_POST} outbox events — the
 * consumer half of decoupled, non-destructive General-Ledger auto-posting (Reqs 8.5, 9.4, 10.4,
 * 17.4). Mirrors {@code WhatsAppOutboxDrainer} / {@code EmailOutboxDrainer}: it polls {@code PENDING}
 * rows that are due (never attempted, or past their backoff window), does its work, and records the
 * outcome on the row.
 *
 * <p>For each due row it parses the source key ({@code sourceType} + {@code sourceId}) back from the
 * payload and:
 * <ul>
 *   <li><b>already posted</b> &rarr; if a voucher already exists for the source document
 *       ({@link SourcePostingGuard#alreadyPosted}), the event is marked {@code SENT} as a no-op
 *       success (Reqs 8.4, 9.3, 10.3, 11.4);</li>
 *   <li><b>success</b> &rarr; {@link LedgerAutoPostingService#buildDraft} derives a balanced draft,
 *       {@link VoucherService#postForSource} posts it (stamping the source key for idempotency), the
 *       {@code source → voucher} trace is recorded, and the event is marked {@code SENT};</li>
 *   <li><b>failure with retries left</b> &rarr; the event stays {@code PENDING}, {@code attempts} is
 *       incremented, {@code last_error} is recorded, and {@code next_attempt_at} is pushed out by the
 *       configured backoff (transient errors self-heal);</li>
 *   <li><b>failure with retries exhausted</b> (e.g. a derivation/imbalance error) &rarr; the event is
 *       marked {@code FAILED} with the error and an ADMIN in-app notification is raised for review
 *       (Reqs 8.5, 9.4, 10.4).</li>
 * </ul>
 *
 * <p><strong>Non-destructive guarantee (Req 17.4).</strong> The source business event was committed
 * in its own transaction; this drainer only <em>reads</em> the source aggregate (via
 * {@code buildDraft}) and posts a new voucher. It never updates or deletes the source order / PO /
 * expense / payment, and a posting failure only ever marks the outbox row {@code FAILED} + notifies —
 * it makes no compensating change. Each event's outcome is persisted independently, so one poisoned
 * source document does not stall the rest of the queue.
 */
@Component
public class LedgerPostingDrainer {

    private static final Logger log = LoggerFactory.getLogger(LedgerPostingDrainer.class);

    /** Admin-notification type for a permanently-failed ledger auto-post (console filter/label). */
    private static final String NOTIFICATION_TYPE = "LEDGER_POST_FAILED";

    private final OutboxEventRepository outboxEventRepository;
    private final LedgerAutoPostingService ledgerAutoPostingService;
    private final VoucherService voucherService;
    private final SourcePostingGuard sourcePostingGuard;
    private final StaffNotificationDispatcher staffNotificationDispatcher;

    /** Maximum delivery attempts before a row is marked FAILED (config-driven, like the WhatsApp drainer). */
    @Value("${app.ledger.autopost.max-attempts:5}")
    private int maxAttempts;

    /** Backoff between retry attempts (ms). */
    @Value("${app.ledger.autopost.retry-backoff-ms:60000}")
    private long retryBackoffMs;

    public LedgerPostingDrainer(OutboxEventRepository outboxEventRepository,
                                LedgerAutoPostingService ledgerAutoPostingService,
                                VoucherService voucherService,
                                SourcePostingGuard sourcePostingGuard,
                                StaffNotificationDispatcher staffNotificationDispatcher) {
        this.outboxEventRepository = outboxEventRepository;
        this.ledgerAutoPostingService = ledgerAutoPostingService;
        this.voucherService = voucherService;
        this.sourcePostingGuard = sourcePostingGuard;
        this.staffNotificationDispatcher = staffNotificationDispatcher;
    }

    /** Scheduled entry point: drains due ledger-post events (default every 20 seconds). */
    @Scheduled(fixedDelayString = "${app.ledger.autopost.drain-interval-ms:20000}")
    public void scheduledDrain() {
        try {
            drainPostings();
        } catch (RuntimeException e) {
            log.warn("Ledger auto-posting drain cycle failed: {}", e.getMessage());
        }
    }

    /**
     * Drains all currently-due ledger-post events once.
     *
     * @return the number of events attempted this cycle
     */
    public int drainPostings() {
        LocalDateTime now = LocalDateTime.now();
        List<OutboxEvent> due = outboxEventRepository.findDue(
                OutboxEvent.EVENT_LEDGER_POST, OutboxEvent.STATUS_PENDING, now);
        int processed = 0;
        for (OutboxEvent event : due) {
            processOne(event);
            processed++;
        }
        return processed;
    }

    private void processOne(OutboxEvent event) {
        Map<String, Object> payload = payload(event);
        SourceType sourceType;
        Long sourceId;
        try {
            sourceType = SourceType.valueOf(asString(payload.get("sourceType")));
            sourceId = asLong(payload.get("sourceId"));
            if (sourceId == null) {
                throw new IllegalArgumentException("missing sourceId");
            }
        } catch (RuntimeException malformed) {
            // A malformed/unparseable payload can never succeed on retry — fail it immediately and
            // notify, rather than looping. The source aggregate is untouched (Req 17.4).
            String error = "Malformed LEDGER_POST payload: " + malformed.getMessage();
            event.markFailed(error);
            outboxEventRepository.save(event);
            raiseAdminNotification(event, "unknown", error);
            log.warn("Ledger auto-post event {} has a malformed payload: {}", event.getId(), error);
            return;
        }

        try {
            // Idempotency pre-check (Reqs 8.4, 9.3, 10.3, 11.4): a voucher already exists → no-op success.
            if (sourcePostingGuard.alreadyPosted(sourceType, sourceId)) {
                event.markSent();
                outboxEventRepository.save(event);
                log.debug("Ledger auto-post for {} #{} already posted; marking event {} SENT (no-op).",
                        sourceType, sourceId, event.getId());
                return;
            }

            // Derive (read-only) + post the balanced voucher, stamping the source key for idempotency.
            DraftVoucher draft = ledgerAutoPostingService.buildDraft(sourceType, sourceId);
            Voucher voucher = voucherService.postForSource(draft, sourceType.name(), sourceId);
            sourcePostingGuard.recordPosting(sourceType, sourceId, voucher.getId());

            event.markSent();
            outboxEventRepository.save(event);
            log.debug("Ledger auto-post for {} #{} posted voucher {}; marking event {} SENT.",
                    sourceType, sourceId, voucher.getVoucherReference(), event.getId());
        } catch (RuntimeException ex) {
            handleFailure(event, sourceType, sourceId, ex);
        }
    }

    /**
     * Records a failed posting attempt with bounded retries. A transient error keeps the row
     * {@code PENDING} with a backoff; once retries are exhausted (e.g. a persistent derivation /
     * imbalance error) the row is marked {@code FAILED} and an ADMIN notification is raised (Reqs 8.5,
     * 9.4, 10.4). The source aggregate is never touched (Req 17.4).
     */
    private void handleFailure(OutboxEvent event, SourceType sourceType, Long sourceId, RuntimeException ex) {
        String error = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
        boolean exhausted = event.getAttempts() + 1 >= maxAttempts;
        if (exhausted) {
            event.markFailed(error);
            outboxEventRepository.save(event);
            raiseAdminNotification(event, sourceType + " #" + sourceId, error);
            log.warn("Ledger auto-post for {} #{} failed after {} attempts: {}",
                    sourceType, sourceId, event.getAttempts(), error);
        } else {
            LocalDateTime next = LocalDateTime.now().plus(Duration.ofMillis(retryBackoffMs));
            event.recordRetry(error, next);
            outboxEventRepository.save(event);
            log.debug("Ledger auto-post for {} #{} will retry at {} ({})",
                    sourceType, sourceId, next, error);
        }
    }

    /** Raises an ADMIN in-app notification flagging a ledger auto-post failure for review (Req 8.5). */
    private void raiseAdminNotification(OutboxEvent event, String source, String error) {
        String detail = "Automatic ledger posting failed for " + source + ": " + error
                + ". The source record is unchanged; review and post manually if required.";
        staffNotificationDispatcher.dispatchToRole(
                NOTIFICATION_TYPE,
                "Ledger auto-posting failed",
                detail,
                AdminNotification.SEVERITY_DANGER,
                null,
                null,
                event.getId(),
                Role.ADMIN);
    }

    private Map<String, Object> payload(OutboxEvent event) {
        Map<String, Object> payload = event.getPayload();
        return payload == null ? Map.of() : payload;
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Long asLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }
}
