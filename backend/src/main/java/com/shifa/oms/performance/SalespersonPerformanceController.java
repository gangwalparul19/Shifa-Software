package com.shifa.oms.performance;

import com.shifa.oms.common.ApiException;
import com.shifa.oms.performance.dto.SalespersonPerformanceDetail;
import com.shifa.oms.performance.dto.SalespersonPerformanceSummary;
import com.shifa.oms.performance.dto.SalesTargetRow;
import com.shifa.oms.performance.dto.SetPeriodTargetRequest;
import com.shifa.oms.performance.dto.SetSalesTargetRequest;
import com.shifa.oms.performance.dto.SetTeamTargetRequest;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * "Salesperson 360" performance endpoints for admins (FEATURE request).
 *
 * <p>Uses a dedicated {@code /api/admin/salespeople} base path (rather than
 * {@code /api/admin/staff}) so the leaderboard path never collides with the
 * staff directory's {@code /{id}} variable route. ADMIN only.
 */
@RestController
@RequestMapping("/api/admin/salespeople")
@PreAuthorize("hasRole('ADMIN')")
public class SalespersonPerformanceController {

    private final SalespersonPerformanceService performanceService;
    private final SalespersonPerformanceExportService exportService;
    private final SalesTargetService salesTargetService;

    public SalespersonPerformanceController(SalespersonPerformanceService performanceService,
                                            SalespersonPerformanceExportService exportService,
                                            SalesTargetService salesTargetService) {
        this.performanceService = performanceService;
        this.exportService = exportService;
        this.salesTargetService = salesTargetService;
    }

    /** Team leaderboard — headline metrics for every salesperson (best month first). */
    @GetMapping("/performance")
    public List<SalespersonPerformanceSummary> leaderboard() {
        return performanceService.leaderboard();
    }

    /** One performance owner's full 360: summary + daily trend + recent orders + lead metrics. */
    @GetMapping("/{id}/performance")
    public SalespersonPerformanceDetail detail(@PathVariable Long id,
                                               @RequestParam(required = false) Integer days) {
        return performanceService.detail(id, days);
    }

    @GetMapping("/performance/export")
    public ResponseEntity<byte[]> exportTeam(@RequestParam(defaultValue = "MONTH") String period,
                                             @RequestParam(defaultValue = "xlsx") String format) {
        PerformancePeriod selected = parsePeriod(period);
        byte[] body = exportService.exportTeam(selected, format);
        return fileResponse(body, "salespeople-performance-" + selected.name().toLowerCase(), format);
    }

    /** Download the complete 360 report for one salesperson and selected period. */
    @GetMapping("/{id}/performance/export")
    public ResponseEntity<byte[]> exportPerson(@PathVariable Long id,
                                               @RequestParam(defaultValue = "MONTH") String period,
                                               @RequestParam(defaultValue = "xlsx") String format) {
        PerformancePeriod selected = parsePeriod(period);
        byte[] body = exportService.exportPerson(id, selected, format);
        return fileResponse(body, "salesperson-" + id + "-" + selected.name().toLowerCase(), format);
    }

    private static PerformancePeriod parsePeriod(String value) {
        try {
            return PerformancePeriod.from(value);
        } catch (IllegalArgumentException e) {
            throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "UNKNOWN_PERFORMANCE_PERIOD", e.getMessage());
        }
    }

    private static ResponseEntity<byte[]> fileResponse(byte[] body, String baseName, String format) {
        String normalized = format == null ? "" : format.trim().toLowerCase();
        return switch (normalized) {
            case "xlsx", "excel" -> ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"" + baseName + ".xlsx\"")
                    .body(body);
            case "pdf" -> ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_PDF)
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"" + baseName + ".pdf\"")
                    .body(body);
            default -> throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "UNSUPPORTED_FORMAT",
                    "Unsupported export format: " + format + " (expected xlsx or pdf).");
        };
    }


    /** Sales targets vs achievement for a month (FEATURE-ROADMAP §6.1); default current month. */
    @GetMapping("/targets")
    public List<SalesTargetRow> targets(@RequestParam(required = false) String month) {
        return salesTargetService.list(month);
    }

    /** Set/update a salesperson's monthly target + optional incentive (FEATURE-ROADMAP §6.1). */
    @PutMapping("/targets")
    public SalesTargetRow setTarget(@Valid @RequestBody SetSalesTargetRequest request) {
        return salesTargetService.setTarget(
                request.salespersonId(), request.month(), request.targetAmount(), request.incentivePct());
    }

    // --- CRM expansion Phase 2 (Req 12): weekly + team targets --------------

    /** Weekly per-salesperson target vs achievement for a week (default current week). */
    @GetMapping("/targets/weekly")
    public List<SalesTargetRow> weeklyTargets(
            @RequestParam(name = "week", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate week) {
        return salesTargetService.listWeekly(week);
    }

    /** Set/update a per-salesperson WEEK or MONTH target (+ optional conversion goal). */
    @PutMapping("/targets/period")
    public SalesTargetRow setPeriodTarget(@Valid @RequestBody SetPeriodTargetRequest request) {
        return salesTargetService.setSalespersonTarget(
                request.salespersonId(), request.periodType(), request.anchor(),
                request.targetAmount(), request.incentivePct(), request.conversionTarget());
    }

    /** Set/update a TEAM target (keyed by the team lead) for a WEEK or MONTH period. */
    @PutMapping("/targets/team")
    public SalesTargetRow setTeamTarget(@Valid @RequestBody SetTeamTargetRequest request) {
        return salesTargetService.setTeamTarget(
                request.teamLeadId(), request.periodType(), request.anchor(),
                request.targetAmount(), request.conversionTarget());
    }
}
