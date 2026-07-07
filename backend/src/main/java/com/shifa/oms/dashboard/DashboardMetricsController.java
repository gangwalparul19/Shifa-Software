package com.shifa.oms.dashboard;

import com.shifa.oms.common.ApiException;
import com.shifa.oms.dashboard.dto.ActivityCards;
import com.shifa.oms.dashboard.dto.DashboardMetricsResponse;
import com.shifa.oms.dashboard.dto.LiveStats;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * Admin dashboard metrics API (Req 19.1&ndash;19.7).
 *
 * <p>Admin-only (Req 5.4): the dashboard aggregates every salesperson's orders,
 * so it is never salesperson-scoped. The server is authoritative; the Angular
 * {@code adminOnlyGuard} only mirrors this for UX.
 *
 * <ul>
 *   <li>{@code GET /api/admin/metrics?period=&bucket=&from=&to=} &mdash; metric
 *       cards, the sales graph with previous-period comparison + % change, and
 *       top performers for the selected time period (Req 19.1&ndash;19.4, 19.7).</li>
 *   <li>{@code GET /api/admin/metrics/live} &mdash; the real-time live stats
 *       (Req 19.5); also pushed over the SSE stream.</li>
 *   <li>{@code GET /api/admin/metrics/activity} &mdash; the activity-card counts
 *       (Req 19.6); also pushed over the SSE stream.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/admin/metrics")
@PreAuthorize("hasRole('ADMIN')")
public class DashboardMetricsController {

    private final DashboardMetricsService metricsService;

    public DashboardMetricsController(DashboardMetricsService metricsService) {
        this.metricsService = metricsService;
    }

    /** Dashboard metric cards, sales graph, and top performers for a period (Req 19.1&ndash;19.4, 19.7). */
    @GetMapping
    public DashboardMetricsResponse metrics(
            @RequestParam(required = false) String period,
            @RequestParam(required = false) String bucket,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return metricsService.metrics(parsePeriod(period), parseBucket(bucket), from, to);
    }

    /** Real-time live statistics (Req 19.5). */
    @GetMapping("/live")
    public LiveStats live() {
        return metricsService.liveStats();
    }

    /** Activity-card counts (Req 19.6). */
    @GetMapping("/activity")
    public ActivityCards activity() {
        return metricsService.activityCards();
    }

    private static MetricsPeriod parsePeriod(String raw) {
        try {
            return MetricsPeriod.from(raw);
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "UNKNOWN_PERIOD", e.getMessage());
        }
    }

    private static SalesBucket parseBucket(String raw) {
        try {
            return SalesBucket.from(raw);
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "UNKNOWN_BUCKET", e.getMessage());
        }
    }
}
