package com.shifa.oms.dashboard;

import com.shifa.oms.adminnotification.StaffNotificationDispatcher;
import com.shifa.oms.auth.Role;
import com.shifa.oms.dashboard.dto.OwnerSnapshotResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Owner threshold-alert push (ENHANCEMENT 3.5).
 *
 * <p>Once a day it evaluates the {@link OwnerSnapshotService owner snapshot} and,
 * when something needs the owner's attention, raises an ADMIN in-app notification
 * that also fires a browser push (via {@link StaffNotificationDispatcher}, which
 * is a no-op for push until VAPID is configured — the in-app bell always works).
 * This turns the "owner PWA home" (the dashboard "Today at a glance" card, 1.2)
 * into something that actively reaches the owner on their phone when it matters,
 * rather than only when they open the app.
 *
 * <p>Three threshold alerts, each raised at most once per day (idempotent via a
 * date-derived source id so a restart / re-run never double-sends):
 * <ul>
 *   <li>COD past the courier SLA — money to chase;</li>
 *   <li>stuck shipments (QuikShipX rejected) — need re-routing;</li>
 *   <li>a spike in failed deliveries beyond a configurable count.</li>
 * </ul>
 *
 * <p>Gated by {@code app.dashboard.owner-alerts-enabled} (default true) +
 * {@code OWNER_ALERT_CRON} (default 08:15, just after the daily report). Failures
 * are logged, never rethrown.
 */
@Component
public class OwnerAlertJob {

    private static final Logger log = LoggerFactory.getLogger(OwnerAlertJob.class);
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Kolkata");

    /**
     * A large base above any real DB event id so the per-day idempotency keys used
     * for owner alerts never collide with genuine outbox/notification event ids.
     */
    private static final long ALERT_ID_BASE = 9_000_000_000L;

    private final OwnerSnapshotService ownerSnapshotService;
    private final StaffNotificationDispatcher dispatcher;
    private final boolean enabled;
    private final int failureSpikeThreshold;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public OwnerAlertJob(OwnerSnapshotService ownerSnapshotService,
                         StaffNotificationDispatcher dispatcher,
                         @Value("${app.dashboard.owner-alerts-enabled:true}") boolean enabled,
                         @Value("${app.dashboard.owner-alert-failure-threshold:3}") int failureSpikeThreshold) {
        this(ownerSnapshotService, dispatcher, enabled, failureSpikeThreshold, Clock.system(BUSINESS_ZONE));
    }

    /** Package-visible constructor allowing a fixed clock in tests. */
    OwnerAlertJob(OwnerSnapshotService ownerSnapshotService,
                  StaffNotificationDispatcher dispatcher,
                  boolean enabled,
                  int failureSpikeThreshold,
                  Clock clock) {
        this.ownerSnapshotService = ownerSnapshotService;
        this.dispatcher = dispatcher;
        this.enabled = enabled;
        this.failureSpikeThreshold = failureSpikeThreshold;
        this.clock = clock;
    }

    /** Scheduled entry point: evaluate thresholds and push owner alerts. */
    @Scheduled(cron = "${OWNER_ALERT_CRON:0 15 8 * * *}")
    public void evaluate() {
        if (!enabled) {
            log.debug("Owner threshold alerts skipped: app.dashboard.owner-alerts-enabled=false.");
            return;
        }
        try {
            raiseAlerts(ownerSnapshotService.snapshot());
        } catch (RuntimeException e) {
            log.warn("Owner alert cycle failed: {}", e.getMessage());
        }
    }

    /**
     * Raises the applicable threshold alerts for a snapshot (extracted for direct
     * testing). Each uses a date+code idempotency key so it fires at most once/day.
     */
    void raiseAlerts(OwnerSnapshotResponse s) {
        if (s == null) {
            return;
        }
        long day = LocalDate.now(clock).toEpochDay();

        if (s.codOverSla() > 0) {
            dispatcher.dispatchToRole(
                    "OWNER_ALERT",
                    "COD past courier SLA",
                    s.codOverSla() + " COD order(s) are overdue from the courier — chase the payout.",
                    "warning", null, null, alertId(day, 1), Role.ADMIN);
        }
        if (s.stuckShipments() > 0) {
            dispatcher.dispatchToRole(
                    "OWNER_ALERT",
                    "Shipments need re-routing",
                    s.stuckShipments() + " order(s) were rejected by the courier and need re-routing to in-house.",
                    "danger", null, null, alertId(day, 2), Role.ADMIN);
        }
        if (s.failedDeliveries() >= failureSpikeThreshold) {
            dispatcher.dispatchToRole(
                    "OWNER_ALERT",
                    "Delivery failures need attention",
                    s.failedDeliveries() + " order(s) are in a failed-delivery state — decide retry or RTO.",
                    "danger", null, null, alertId(day, 3), Role.ADMIN);
        }
    }

    /** A per-day, per-alert idempotency id in a namespace above real event ids. */
    private static long alertId(long epochDay, int alertCode) {
        return ALERT_ID_BASE + epochDay * 10 + alertCode;
    }
}
