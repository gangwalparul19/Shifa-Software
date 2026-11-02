package com.shifa.oms.dashboard;

import com.shifa.oms.dashboard.dto.TeamsOverviewResponse;
import com.shifa.oms.performance.PerformancePeriod;
import com.shifa.oms.reporting.ReportFilenames;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * "Team-wise sales with status" admin dashboard view ({@code GET
 * /api/admin/dashboard/teams}, ADMIN only): every team lead's combined
 * orders/revenue/delivery KPIs, current lead pipeline, and a ranked call-out
 * list of due/overdue leads, so an admin can see where each team is heading
 * and jump straight to the leads that need a call.
 *
 * <p>Orders and revenue follow the chosen period (this month when none is given); the same
 * period drives the Excel and PDF download.
 */
@RestController
@RequestMapping("/api/admin/dashboard")
@PreAuthorize("hasRole('ADMIN')")
public class TeamsOverviewController {

    private final TeamsOverviewService service;
    private final TeamsOverviewExportService exportService;

    public TeamsOverviewController(TeamsOverviewService service, TeamsOverviewExportService exportService) {
        this.service = service;
        this.exportService = exportService;
    }

    /**
     * {@code period} is one of TODAY, YESTERDAY, LAST_7_DAYS, WEEK, MONTH (default), LAST_MONTH,
     * OVERALL or CUSTOM; CUSTOM needs {@code from} and {@code to} (both days included).
     */
    @GetMapping("/teams")
    public TeamsOverviewResponse teams(
            @RequestParam(required = false) String period,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return service.overview(PerformancePeriod.parse(period), from, to);
    }

    /**
     * Downloads the team-wise sales report for the chosen period. {@code team} is {@code all}
     * (default), {@code unassigned} or a team lead's user id; {@code format} is {@code xlsx} or
     * {@code pdf}.
     */
    @GetMapping("/teams/export")
    public ResponseEntity<byte[]> export(
            @RequestParam(required = false) String period,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String team,
            @RequestParam(defaultValue = "xlsx") String format) {
        TeamsOverviewExportService.Export export =
                exportService.export(PerformancePeriod.parse(period), from, to, team, format);
        return ResponseEntity.ok()
                .contentType(export.contentType())
                .header(HttpHeaders.CONTENT_DISPOSITION, ReportFilenames.contentDisposition(export.filename()))
                .body(export.body());
    }
}
