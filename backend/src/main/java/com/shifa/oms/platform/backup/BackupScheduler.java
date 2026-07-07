package com.shifa.oms.platform.backup;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Triggers {@link BackupService#runBackup()} on the configured schedule
 * (Req 24.1).
 *
 * <p>The cron expression comes from {@code app.backup.cron} (default
 * {@code 0 0 2 * * *}, i.e. daily at 02:00), whose firing interval is at most
 * 24h so the platform backs up "at least once every 24 hours" (Req 24.1). The
 * service records the outcome and notifies the admin on failure (Req 24.2); this
 * scheduler only invokes it and never throws, so a failed backup cannot stall
 * the scheduler thread.
 */
@Component
public class BackupScheduler {

    private static final Logger log = LoggerFactory.getLogger(BackupScheduler.class);

    private final BackupService backupService;

    public BackupScheduler(BackupService backupService) {
        this.backupService = backupService;
    }

    /** Scheduled entry point: run the nightly backup per {@code app.backup.cron}. */
    @Scheduled(cron = "${app.backup.cron:0 0 2 * * *}")
    public void scheduledBackup() {
        try {
            backupService.runBackup();
        } catch (RuntimeException e) {
            // Defensive: runBackup() already handles/records failures, but never
            // let an unexpected error escape the scheduler thread.
            log.error("Scheduled backup invocation failed unexpectedly: {}", e.getMessage());
        }
    }
}
