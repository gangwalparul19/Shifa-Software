package com.shifa.oms.mail.report;

import com.shifa.oms.mail.MailProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * Scheduled trigger for the consolidated daily report email (Consolidated Daily
 * Report feature).
 *
 * <p>Once a day at <strong>8 AM</strong> (default cron {@code 0 0 8 * * *},
 * overridable via {@code REPORT_DIGEST_CRON}) it asks {@link DailyReportService}
 * to build and send the <em>previous</em> day's consolidated report.
 *
 * <p><strong>Double-send guard.</strong> The internal schedule only fires when
 * {@code app.mail.digest-enabled} / {@code REPORT_DIGEST_ENABLED} is {@code true}
 * (the default). Deployments where an external AWS Lambda drives the report via
 * {@code POST /api/admin/reports/daily-digest/run} at 8 AM IST should set
 * {@code REPORT_DIGEST_ENABLED=false} so the report is not sent twice. The ADMIN
 * trigger endpoint works regardless of this flag.
 *
 * <p>Failures are logged, never rethrown, so a mail outage can never stall the
 * scheduler thread.
 */
@Component
public class DailyReportJob {

    private static final Logger log = LoggerFactory.getLogger(DailyReportJob.class);

    private final DailyReportService dailyReportService;
    private final MailProperties mailProperties;

    public DailyReportJob(DailyReportService dailyReportService, MailProperties mailProperties) {
        this.dailyReportService = dailyReportService;
        this.mailProperties = mailProperties;
    }

    /**
     * Scheduled entry point: builds and (if enabled + configured) sends
     * yesterday's consolidated report.
     */
    @Scheduled(cron = "${REPORT_DIGEST_CRON:0 0 8 * * *}")
    public void sendDailyReport() {
        if (!mailProperties.isDigestEnabled()) {
            log.debug("Consolidated daily report skipped: internal scheduler disabled "
                    + "(app.mail.digest-enabled=false; external trigger expected).");
            return;
        }
        try {
            dailyReportService.sendConsolidatedReportFor(LocalDate.now().minusDays(1));
        } catch (RuntimeException e) {
            log.warn("Consolidated daily report cycle failed: {}", e.getMessage());
        }
    }
}
