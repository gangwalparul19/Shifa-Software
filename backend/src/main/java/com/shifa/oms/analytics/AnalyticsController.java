package com.shifa.oms.analytics;

import com.shifa.oms.analytics.dto.ForecastReport;
import com.shifa.oms.analytics.dto.GrowthAnalytics.CustomerSegmentsReport;
import com.shifa.oms.analytics.dto.GrowthAnalytics.DatasetInfo;
import com.shifa.oms.analytics.dto.GrowthAnalytics.OperationsReport;
import com.shifa.oms.analytics.dto.GrowthAnalytics.OverviewReport;
import com.shifa.oms.analytics.dto.GrowthAnalytics.PointersReport;
import com.shifa.oms.analytics.dto.GrowthAnalytics.ProductPerformanceReport;
import com.shifa.oms.analytics.dto.RetentionReport;
import com.shifa.oms.audit.AuditActions;
import com.shifa.oms.audit.AuditService;
import com.shifa.oms.reporting.ReportFilenames;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * Admin analytics API: cohort/retention and demand/cash forecasting (FEATURE-ROADMAP
 * §6.3, §6.5), delivery performance, and the growth analytics (overview, customers,
 * products, operations, key pointers and downloadable datasets). ADMIN only.
 *
 * <p>Period parameters {@code from} / {@code to} are IST calendar dates, inclusive.
 * Reports that compare against the previous period use the equally long span right
 * before {@code from}.
 */
@RestController
@RequestMapping("/api/admin/analytics")
@PreAuthorize("hasRole('ADMIN')")
public class AnalyticsController {

    private final RetentionService retentionService;
    private final ForecastService forecastService;
    private final DeliveryPerformanceService deliveryPerformanceService;
    private final GrowthAnalyticsService growthAnalyticsService;
    private final AnalyticsDatasetService datasetService;
    private final AuditService auditService;

    public AnalyticsController(RetentionService retentionService,
                               ForecastService forecastService,
                               DeliveryPerformanceService deliveryPerformanceService,
                               GrowthAnalyticsService growthAnalyticsService,
                               AnalyticsDatasetService datasetService,
                               AuditService auditService) {
        this.retentionService = retentionService;
        this.forecastService = forecastService;
        this.deliveryPerformanceService = deliveryPerformanceService;
        this.growthAnalyticsService = growthAnalyticsService;
        this.datasetService = datasetService;
        this.auditService = auditService;
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

    /**
     * Delivery-performance analytics (ENHANCEMENT 3.3): delivered-vs-failed rates
     * by courier / destination state / pincode band, worst-first, so the owner can
     * target high-RTO regions and weak couriers.
     */
    @GetMapping("/delivery-performance")
    public com.shifa.oms.analytics.dto.DeliveryPerformanceReport deliveryPerformance() {
        return deliveryPerformanceService.report();
    }

    /** Sales, customers, mixes, destinations and order timing for a period, against the previous one. */
    @GetMapping("/overview")
    public OverviewReport overview(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return growthAnalyticsService.overview(from, to);
    }

    /** Customer engagement segments and value concentration, all time. */
    @GetMapping("/customers")
    public CustomerSegmentsReport customers() {
        return growthAnalyticsService.customers();
    }

    /** Product performance, ABC classes and products bought together for a period. */
    @GetMapping("/products")
    public ProductPerformanceReport products(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return growthAnalyticsService.products(from, to);
    }

    /** Fulfilment speed, order outcomes, payment risk and rejection reasons for a period. */
    @GetMapping("/operations")
    public OperationsReport operations(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return growthAnalyticsService.operations(from, to);
    }

    /** The key things worth acting on in a period, ranked. */
    @GetMapping("/pointers")
    public PointersReport pointers(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return growthAnalyticsService.pointers(from, to);
    }

    /** The downloadable datasets and their columns. */
    @GetMapping("/datasets")
    public List<DatasetInfo> datasets() {
        return datasetService.catalog();
    }

    /** One dataset as a CSV download. */
    @GetMapping("/datasets/{key}/export")
    public ResponseEntity<byte[]> exportDataset(
            @PathVariable String key,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        AnalyticsDatasetService.Export export = datasetService.export(key, from, to);
        auditService.record(AuditActions.ANALYTICS_EXPORTED, AuditActions.ENTITY_ANALYTICS, key,
                "Downloaded the " + key + " dataset (" + export.rows() + " rows, "
                        + ReportFilenames.rangePart(from, to) + ")");
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(export.contentType() + "; charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION, ReportFilenames.contentDisposition(export.filename()))
                .body(export.content());
    }
}
