package com.shifa.oms.mail;

import com.shifa.oms.audit.AuditActions;
import com.shifa.oms.audit.AuditService;
import com.shifa.oms.mail.ReportRecipientService.Overview;
import com.shifa.oms.mail.report.DailyReportService;
import com.shifa.oms.mail.report.DailyReportService.SendResult;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * Settings → Email: who receives the admin report emails, and a "Send now" button.
 *
 * <p>ADMIN only. Every change returns the refreshed list, so the screen never shows stale rows.
 */
@RestController
@RequestMapping("/api/admin/report-recipients")
@PreAuthorize("hasRole('ADMIN')")
@Validated
public class ReportRecipientController {

    private final ReportRecipientService service;
    private final DailyReportService reportService;
    private final AuditService auditService;

    public ReportRecipientController(ReportRecipientService service,
                                     DailyReportService reportService,
                                     AuditService auditService) {
        this.service = service;
        this.reportService = reportService;
        this.auditService = auditService;
    }

    public record AddRequest(@Size(max = 255) String email, @Size(max = 100) String name) {
    }

    public record ActiveRequest(boolean active) {
    }

    /**
     * @param date   the day to report on; blank means yesterday
     * @param emails the addresses to send to; each must be on the recipient list
     */
    public record SendRequest(LocalDate date, @Size(max = 200) List<String> emails) {
    }

    /** Every admin user, every added address and every server address, with what each one receives. */
    @GetMapping
    public Overview list() {
        return service.overview();
    }

    /** Adds an address that will receive every report from now on. */
    @PostMapping
    public Overview add(@RequestBody AddRequest request) {
        service.add(request == null ? null : request.email(), request == null ? null : request.name());
        return service.overview();
    }

    /** Pauses or resumes an added address. */
    @PutMapping("/{id}/active")
    public Overview setActive(@PathVariable Long id, @RequestBody ActiveRequest request) {
        service.setActive(id, request != null && request.active());
        return service.overview();
    }

    /** Removes an added address. */
    @DeleteMapping("/{id}")
    public Overview remove(@PathVariable Long id) {
        service.remove(id);
        return service.overview();
    }

    /** Sends the morning report now to the chosen addresses. */
    @PostMapping("/send")
    public SendResult send(@RequestBody SendRequest request) {
        SendResult result = reportService.sendMorningBriefTo(
                request == null ? null : request.date(), request == null ? null : request.emails());
        auditService.record(AuditActions.REPORT_SENT_MANUALLY, AuditActions.ENTITY_REPORT,
                String.valueOf(result.date()),
                "Sent the morning report for " + result.date() + " to " + result.sent() + " of "
                        + result.requested() + " chosen recipient(s)"
                        + (result.failures().isEmpty() ? "." : ", " + result.failures().size() + " failed."));
        return result;
    }
}
