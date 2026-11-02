package com.shifa.oms.salesperson;

import com.shifa.oms.auth.CurrentUserService;
import com.shifa.oms.performance.PerformancePeriod;
import com.shifa.oms.salesperson.dto.LeaderboardResponse;
import com.shifa.oms.salesperson.dto.MyDayResponse;
import com.shifa.oms.salesperson.dto.ReorderDueCustomer;
import com.shifa.oms.salesperson.dto.WinBackCustomer;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * Salesperson self-service "My Day" + "Win-back" API ({@code /api/my-day}).
 *
 * <p>Available to SALESPERSON (own data, scoped server-side) and ADMIN (whole
 * business). The caller is resolved from the security context; the role is never
 * trusted from the client.
 */
@RestController
@RequestMapping("/api/my-day")
@PreAuthorize("hasAnyRole('SALESPERSON','ADMIN')")
public class MyDayController {

    private final MyDayService myDayService;
    private final LeaderboardExportService leaderboardExportService;
    private final CurrentUserService currentUserService;

    public MyDayController(MyDayService myDayService,
                           LeaderboardExportService leaderboardExportService,
                           CurrentUserService currentUserService) {
        this.myDayService = myDayService;
        this.leaderboardExportService = leaderboardExportService;
        this.currentUserService = currentUserService;
    }

    /** Today + month-to-date figures vs. target, and payments to chase. */
    @GetMapping
    public MyDayResponse myDay() {
        return myDayService.forCaller(currentUserService.requireCurrentUser());
    }

    /** Lapsed customers (no order in the last {@code days} days), highest value first. */
    @GetMapping("/win-back")
    public List<WinBackCustomer> winBack(@RequestParam(value = "days", required = false) Integer days) {
        return myDayService.winBack(currentUserService.requireCurrentUser(), days);
    }

    /** Customers predicted (from cadence) to be due for a repeat order, most overdue first. */
    @GetMapping("/reorder-due")
    public List<ReorderDueCustomer> reorderDue() {
        return myDayService.reorderDue(currentUserService.requireCurrentUser());
    }

    /**
     * Sales leaderboard + the caller's own rank and order streak for a period. {@code period} is
     * one of TODAY, YESTERDAY, LAST_7_DAYS, WEEK, MONTH (the default), LAST_MONTH, OVERALL or
     * CUSTOM; CUSTOM needs {@code from} and {@code to} (both days included).
     */
    @GetMapping("/leaderboard")
    public LeaderboardResponse leaderboard(
            @RequestParam(value = "period", required = false) String period,
            @RequestParam(value = "from", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(value = "to", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return myDayService.leaderboard(
                currentUserService.requireCurrentUser(), PerformancePeriod.parse(period), from, to);
    }

    @GetMapping("/leaderboard/export")
    public ResponseEntity<byte[]> exportLeaderboard(
            @RequestParam(value = "period", required = false) String period,
            @RequestParam(value = "from", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(value = "to", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "xlsx") String format) {
        LeaderboardExportService.Export export = leaderboardExportService.export(
                currentUserService.requireCurrentUser(), PerformancePeriod.parse(period), from, to, format);
        return ResponseEntity.ok()
                .contentType(export.contentType())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        com.shifa.oms.reporting.ReportFilenames.contentDisposition(export.filename()))
                .body(export.body());
    }
}