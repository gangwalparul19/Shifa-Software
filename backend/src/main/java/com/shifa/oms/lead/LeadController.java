package com.shifa.oms.lead;

import com.shifa.oms.auth.AuthPrincipal;
import com.shifa.oms.auth.CurrentUserService;
import com.shifa.oms.lead.dto.CreateLeadRequest;
import com.shifa.oms.lead.dto.FollowUpRequest;
import com.shifa.oms.lead.dto.LeadConvertRequest;
import com.shifa.oms.lead.dto.LeadReports.BySourceReport;
import com.shifa.oms.lead.dto.LeadReports.ConversionReport;
import com.shifa.oms.lead.dto.LeadReports.LostReasonReport;
import com.shifa.oms.lead.dto.LeadReports.PipelineReport;
import com.shifa.oms.lead.dto.LeadResponse;
import com.shifa.oms.lead.dto.LeadStatusChangeRequest;
import com.shifa.oms.lead.dto.LeadSummaryResponse;
import com.shifa.oms.order.LeadSource;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Lead / sales-pipeline REST surface (design &sect;API). All routes require
 * authentication and are restricted to {@code SALESPERSON} and {@code ADMIN} via
 * method-level {@code @PreAuthorize}; salesperson scoping (a salesperson sees /
 * mutates only their own leads, an out-of-scope id is a 404) is enforced inside
 * {@link LeadService}, never trusted from the client.
 *
 * <ul>
 *   <li>{@code POST /api/leads} — capture a lead (Req 1).</li>
 *   <li>{@code GET /api/leads} — list, scoped, with {@code q}/{@code status}/{@code source} filters (Req 3.4).</li>
 *   <li>{@code GET /api/leads/pipeline} — active-lead counts per stage (Req 3.3).</li>
 *   <li>{@code GET /api/leads/follow-ups/due} — the caller's due follow-ups (Req 5.2).</li>
 *   <li>{@code GET /api/leads/{id}} — full detail incl. history (Req 3.5).</li>
 *   <li>{@code POST /api/leads/{id}/status} — advance status / mark LOST (Req 2).</li>
 *   <li>{@code PUT /api/leads/{id}/follow-up} — set/clear the follow-up date (Req 5.1).</li>
 *   <li>{@code PUT /api/leads/{id}} — edit capture fields while non-terminal (Req 3.6).</li>
 *   <li>{@code POST /api/leads/{id}/convert} — convert to an order and mark WON (Req 4).</li>
 *   <li>{@code GET /api/leads/reports/*} — by-source / conversion / pipeline / lost-reasons (Req 6).</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/leads")
@PreAuthorize("hasAnyRole('SALESPERSON','ADMIN')")
public class LeadController {

    private final LeadService leadService;
    private final CurrentUserService currentUserService;

    public LeadController(LeadService leadService, CurrentUserService currentUserService) {
        this.leadService = leadService;
        this.currentUserService = currentUserService;
    }

    /** Capture a new lead (Req 1.1-1.6). */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public LeadResponse capture(@Valid @RequestBody CreateLeadRequest request) {
        AuthPrincipal actor = currentUserService.requireCurrentUser();
        return leadService.capture(request, actor);
    }

    /** Scoped lead list with optional name/mobile search and status/source filters (Req 3.4). */
    @GetMapping
    public List<LeadSummaryResponse> list(
            @RequestParam(name = "q", required = false) String q,
            @RequestParam(name = "status", required = false) LeadStatus status,
            @RequestParam(name = "source", required = false) LeadSource source) {
        AuthPrincipal actor = currentUserService.requireCurrentUser();
        return leadService.list(q, status, source, actor);
    }

    /** Active-lead pipeline counts per {@link LeadStatus} (Req 3.3), scoped. */
    @GetMapping("/pipeline")
    public Map<LeadStatus, Long> pipeline() {
        AuthPrincipal actor = currentUserService.requireCurrentUser();
        return leadService.pipelineCounts(actor);
    }

    /** The acting user's due follow-ups: non-terminal leads due on/before today (Req 5.2). */
    @GetMapping("/follow-ups/due")
    public List<LeadSummaryResponse> dueFollowUps() {
        AuthPrincipal actor = currentUserService.requireCurrentUser();
        return leadService.dueFollowUps(actor);
    }

    /** Scoped lead detail incl. status history (Req 3.5); 404 when out of scope. */
    @GetMapping("/{id}")
    public LeadResponse detail(@PathVariable Long id) {
        AuthPrincipal actor = currentUserService.requireCurrentUser();
        return leadService.detail(id, actor);
    }

    /** Advance status / mark LOST (Req 2.2-2.7); 409 on illegal/terminal. */
    @PostMapping("/{id}/status")
    public LeadResponse changeStatus(@PathVariable Long id,
                                     @Valid @RequestBody LeadStatusChangeRequest request) {
        AuthPrincipal actor = currentUserService.requireCurrentUser();
        return leadService.transition(
                id, request.toStatus(), request.lostReason(), request.lostReasonNote(), actor);
    }

    /** Set or clear the follow-up date (Req 5.1). */
    @PutMapping("/{id}/follow-up")
    public LeadResponse setFollowUp(@PathVariable Long id,
                                    @Valid @RequestBody FollowUpRequest request) {
        AuthPrincipal actor = currentUserService.requireCurrentUser();
        return leadService.setFollowUp(id, request.followUpDate(), actor);
    }

    /** Edit capture fields while the lead is non-terminal (Req 3.6); 409 when terminal. */
    @PutMapping("/{id}")
    public LeadResponse edit(@PathVariable Long id,
                             @Valid @RequestBody CreateLeadRequest request) {
        AuthPrincipal actor = currentUserService.requireCurrentUser();
        return leadService.edit(id, request, actor);
    }

    /** Convert a lead into an order and mark it WON (Req 4); 409 if already terminal. */
    @PostMapping("/{id}/convert")
    public LeadResponse convert(@PathVariable Long id,
                                @Valid @RequestBody LeadConvertRequest request) {
        AuthPrincipal actor = currentUserService.requireCurrentUser();
        return leadService.convert(id, request, actor);
    }

    // --- Reports (Req 6; ADMIN unscoped, SALESPERSON scoped to own) ---------

    /** Leads-by-source report over an optional {@code from}/{@code to} window (Req 6.1). */
    @GetMapping("/reports/by-source")
    public BySourceReport reportBySource(
            @RequestParam(name = "from", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(name = "to", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        AuthPrincipal actor = currentUserService.requireCurrentUser();
        return leadService.reportBySource(from, to, actor);
    }

    /** Conversion report (per source and per owner) over an optional window (Req 6.2). */
    @GetMapping("/reports/conversion")
    public ConversionReport reportConversion(
            @RequestParam(name = "from", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(name = "to", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        AuthPrincipal actor = currentUserService.requireCurrentUser();
        return leadService.reportConversion(from, to, actor);
    }

    /** Pipeline snapshot: current active-lead counts per stage (Req 6.3). */
    @GetMapping("/reports/pipeline")
    public PipelineReport reportPipeline() {
        AuthPrincipal actor = currentUserService.requireCurrentUser();
        return leadService.reportPipeline(actor);
    }

    /** Lost-reasons report over an optional window (Req 6.4). */
    @GetMapping("/reports/lost-reasons")
    public LostReasonReport reportLostReasons(
            @RequestParam(name = "from", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(name = "to", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        AuthPrincipal actor = currentUserService.requireCurrentUser();
        return leadService.reportLostReasons(from, to, actor);
    }
}
