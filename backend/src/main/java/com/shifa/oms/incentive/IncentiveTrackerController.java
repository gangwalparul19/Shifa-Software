package com.shifa.oms.incentive;

import com.shifa.oms.common.ValidationException;
import com.shifa.oms.incentive.dto.IncentiveProducts;
import com.shifa.oms.incentive.dto.IncentiveTracker;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.Locale;

/**
 * ADMIN-only daily sales tracker: sales and daily bonus per salesperson, the month calendar with
 * highest and lowest days, and the day-by-day product report, plus their Excel and PDF downloads.
 */
@RestController
@RequestMapping("/api/admin/incentives")
@PreAuthorize("hasRole('ADMIN')")
public class IncentiveTrackerController {

    private final IncentiveTrackerService trackerService;
    private final IncentiveExportService exportService;

    public IncentiveTrackerController(IncentiveTrackerService trackerService, IncentiveExportService exportService) {
        this.trackerService = trackerService;
        this.exportService = exportService;
    }

    /** Everyone's daily sales and bonus for a month ({@code yyyy-MM}, default the current month). */
    @GetMapping("/tracker")
    public IncentiveTracker.Response tracker(@RequestParam(required = false) String month) {
        return trackerService.tracker(parseMonth(month));
    }

    /** Product sales per salesperson per day; defaults to the current month up to today. */
    @GetMapping("/products")
    public IncentiveProducts.Response products(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long personId) {
        return trackerService.products(from, to, personId);
    }

    /**
     * Downloads a report. {@code report} is {@code tracker} (needs {@code month}), {@code calendar}
     * (needs {@code month} and {@code personId}) or {@code products} (optional {@code from},
     * {@code to}, {@code personId}); {@code format} is {@code xlsx} or {@code pdf}.
     */
    @GetMapping("/export")
    public ResponseEntity<byte[]> export(
            @RequestParam String report,
            @RequestParam(required = false) String month,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long personId,
            @RequestParam(defaultValue = "xlsx") String format) {
        IncentiveExportService.Export export = switch (report.trim().toLowerCase(Locale.ROOT)) {
            case "tracker" -> exportService.tracker(parseMonth(month), format);
            case "calendar" -> {
                if (personId == null) {
                    throw new ValidationException("Choose a salesperson for the calendar report.");
                }
                yield exportService.calendar(parseMonth(month), personId, format);
            }
            case "products" -> exportService.products(from, to, personId, format);
            default -> throw new ValidationException("report must be tracker, calendar or products.");
        };
        return ResponseEntity.ok()
                .contentType(export.contentType())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        com.shifa.oms.reporting.ReportFilenames.contentDisposition(export.filename()))
                .body(export.body());
    }

    private YearMonth parseMonth(String month) {
        if (month == null || month.isBlank()) {
            return YearMonth.from(trackerService.today());
        }
        try {
            return YearMonth.parse(month.trim());
        } catch (DateTimeParseException e) {
            throw new ValidationException("month must look like 2026-11.");
        }
    }
}
