package com.shifa.oms.analytics;

import com.shifa.oms.analytics.dto.ForecastReport;
import com.shifa.oms.analytics.dto.RetentionReport;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin analytics API (FEATURE-ROADMAP §6.3, §6.5): cohort/retention and
 * demand/cash forecasting. ADMIN only.
 */
@RestController
@RequestMapping("/api/admin/analytics")
@PreAuthorize("hasRole('ADMIN')")
public class AnalyticsController {

    private final RetentionService retentionService;
    private final ForecastService forecastService;

    public AnalyticsController(RetentionService retentionService, ForecastService forecastService) {
        this.retentionService = retentionService;
        this.forecastService = forecastService;
    }

    /** Cohort / retention analysis over the last {@code months} cohorts (§6.3). */
    @GetMapping("/retention")
    public RetentionReport retention(@RequestParam(required = false) Integer months) {
        return retentionService.report(months);
    }

    /** Demand & cash forecast (§6.5). */
    @GetMapping("/forecast")
    public ForecastReport forecast(@RequestParam(required = false) Integer lookbackDays,
                                   @RequestParam(required = false) Integer horizonDays) {
        return forecastService.report(lookbackDays, horizonDays);
    }
}
