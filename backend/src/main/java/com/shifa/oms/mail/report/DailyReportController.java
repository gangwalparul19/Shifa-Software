package com.shifa.oms.mail.report;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * ADMIN-triggered runner for the consolidated daily report
 * ({@code /api/admin/reports}, Consolidated Daily Report feature).
 *
 * <p>Restricted to the {@code ADMIN} role via method security. Because
 * {@code /api/**} already requires authentication in the existing security
 * config and this method adds the {@code hasRole('ADMIN')} check, no security
 * configuration change is needed: unauthenticated callers get 401 and
 * non-admins 403.
 *
 * <p>This lets an AWS Lambda log in as an admin ({@code POST /api/auth/login})
 * and then POST this endpoint at 8 AM IST to send the previous day's report,
 * which is why the internal scheduler can be disabled via
 * {@code REPORT_DIGEST_ENABLED=false} to avoid a double-send.
 */
@RestController
@RequestMapping("/api/admin/reports")
@PreAuthorize("hasRole('ADMIN')")
public class DailyReportController {

    private final DailyReportService dailyReportService;

    public DailyReportController(DailyReportService dailyReportService) {
        this.dailyReportService = dailyReportService;
    }

    /**
     * Builds and (if a recipient is configured) sends the consolidated daily
     * report for the given day, defaulting to yesterday.
     *
     * @param date optional {@code yyyy-MM-dd} day to summarise; defaults to yesterday
     * @return a small JSON summary: {@code {date, orderCount, totalSales, recipientConfigured}}
     */
    @PostMapping("/daily-digest/run")
    public DailyReportService.Result run(
            @RequestParam(name = "date", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        LocalDate day = date != null ? date : LocalDate.now().minusDays(1);
        return dailyReportService.sendConsolidatedReportFor(day);
    }
}
