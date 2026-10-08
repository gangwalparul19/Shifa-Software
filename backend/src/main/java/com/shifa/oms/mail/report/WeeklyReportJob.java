package com.shifa.oms.mail.report;

import com.shifa.oms.mail.MailProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * Optional WEEKLY consolidated report email (scheduled-report-delivery
 * enhancement).
 *
 * <p><strong>Off by default.</strong> This fires only when
 * {@code app.mail.weekly-enabled} / {@code REPORT_WEEKLY_ENABLED} is {@code true}
 * (default {@code false}) AND a recipient is configured ({@code app.mail.digest-to}),
 * so enabling it is an explicit admin choice and it never starts emailing
 * unexpectedly. When enabled it sends, once a week (default Monday 08:30, cron
 * {@code REPORT_WEEKLY_CRON}), the SAME branded consolidated report as the daily
 * one but covering the previous 7 days — reusing
 * {@link DailyReportService#sendConsolidatedReportForRange}.
 *
 * <p>Failures are logged, never rethrown, so a mail outage can never stall the
 * scheduler thread.
 */
@Component
public class WeeklyReportJob {

    private static final Logger log = LoggerFactory.getLogger(WeeklyReportJob.class);

    private final DailyReportService dailyReportService;
    private final MailProperties mailProperties;

    public WeeklyReportJob(DailyReportService dailyReportService, MailProperties mailProperties) {
        this.dailyReportService = dailyReportService;
        this.mailProperties = mailProperties;
    }

    /** Weekly entry point: builds + (when enabled) sends the previous 7 days' report. */
    @Scheduled(cron = "${REPORT_WEEKLY_CRON:0 30 8 * * MON}")
    public void sendWeeklyReport() {
        if (!mailProperties.isWeeklyEnabled()) {
            log.debug("Weekly consolidated report skipped: disabled (app.mail.weekly-enabled=false).");
            return;
        }
        try {
            LocalDate today = LocalDate.now();
            dailyReportService.sendConsolidatedReportForRange(today.minusDays(7), today.minusDays(1));
        } catch (RuntimeException e) {
            log.warn("Weekly consolidated report cycle failed: {}", e.getMessage());
        }
    }
}
