package com.shifa.oms.insights;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Objects;

/**
 * The nightly statistical-insights computation job (statistical-insights-engine,
 * design §Services, Req 1.1). On its configured schedule it delegates to
 * {@link InsightComputationService#computeForToday()} (which owns the
 * {@code @Transactional} boundary), computing and persisting the day's insights
 * and fanning notifiable ones out to admins.
 *
 * <p>The firing schedule comes from {@code app.insights.nightly.cron} (default
 * {@code 0 0 2 * * *} — daily at 02:00). The job is best-effort: any exception is
 * caught and logged so it never escapes the scheduler thread and stalls other
 * scheduled work. The on-demand counterpart is the admin
 * {@code POST /api/insights/recompute} endpoint.
 *
 * <p>Dual-constructor {@link Clock} (mirrors {@code FollowUpReminderJob}) so the
 * "today" the delegated computation uses is deterministic under test.
 */
@Component
public class InsightNightlyJob {

    private static final Logger log = LoggerFactory.getLogger(InsightNightlyJob.class);

    private final InsightComputationService computationService;
    private final Clock clock;

    /** Production constructor (Spring): uses the system default-zone clock. */
    @Autowired
    public InsightNightlyJob(InsightComputationService computationService) {
        this(computationService, Clock.systemDefaultZone());
    }

    /** Test constructor with a fixed clock (deterministic "today" for logging). */
    public InsightNightlyJob(InsightComputationService computationService, Clock clock) {
        this.computationService = Objects.requireNonNull(computationService, "computationService");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Scheduled entry point: compute today's insights, never letting an exception
     * escape the scheduler thread. Returns the number of insights persisted
     * (useful for tests / logging), or {@code 0} when the run failed.
     */
    @Scheduled(cron = "${app.insights.nightly.cron:0 0 2 * * *}")
    public int run() {
        LocalDate today = LocalDate.now(clock);
        try {
            return computationService.computeForToday();
        } catch (RuntimeException e) {
            log.warn("Nightly insight computation for {} failed: {}", today, e.getMessage(), e);
            return 0;
        }
    }
}
