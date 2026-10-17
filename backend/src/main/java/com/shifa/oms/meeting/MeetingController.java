package com.shifa.oms.meeting;

import com.shifa.oms.auth.AuthPrincipal;
import com.shifa.oms.auth.CurrentUserService;
import com.shifa.oms.meeting.dto.CancelMeetingRequest;
import com.shifa.oms.meeting.dto.MeetingNoShowRequest;
import com.shifa.oms.meeting.dto.MeetingOutcomeRequest;
import com.shifa.oms.meeting.dto.MeetingResponse;
import com.shifa.oms.meeting.dto.ScheduleMeetingRequest;
import jakarta.validation.Valid;
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

import java.util.List;

/**
 * Customer-meeting REST surface (CRM expansion Phase 2, Task 11; Req 9). Open to
 * {@code SALESPERSON} / {@code TEAM_LEAD} / {@code ADMIN}; owner scoping is
 * enforced inside {@link MeetingService}.
 */
@RestController
@RequestMapping("/api/meetings")
@PreAuthorize("hasAnyRole('SALESPERSON','TEAM_LEAD','ADMIN')")
public class MeetingController {

    private final MeetingService meetingService;
    private final CurrentUserService currentUserService;

    public MeetingController(MeetingService meetingService, CurrentUserService currentUserService) {
        this.meetingService = meetingService;
        this.currentUserService = currentUserService;
    }

    /** The caller's (scoped) meetings, optional status filter (Req 9.4). */
    @GetMapping
    public List<MeetingResponse> list(
            @RequestParam(name = "status", required = false) MeetingStatus status) {
        AuthPrincipal actor = currentUserService.requireCurrentUser();
        return meetingService.list(status, actor);
    }

    /** Schedule a meeting (Req 9.1). */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MeetingResponse schedule(@Valid @RequestBody ScheduleMeetingRequest request) {
        AuthPrincipal actor = currentUserService.requireCurrentUser();
        return meetingService.schedule(request, actor);
    }

    /** Reschedule / edit an owned meeting (Req 9.2). */
    @PutMapping("/{id}")
    public MeetingResponse reschedule(@PathVariable Long id,
                                      @Valid @RequestBody ScheduleMeetingRequest request) {
        AuthPrincipal actor = currentUserService.requireCurrentUser();
        return meetingService.reschedule(id, request, actor);
    }

    /** Cancel an owned meeting, optionally with a reason (Req 9.2). */
    @PostMapping("/{id}/cancel")
    public MeetingResponse cancel(@PathVariable Long id,
                                  @Valid @RequestBody(required = false) CancelMeetingRequest request) {
        AuthPrincipal actor = currentUserService.requireCurrentUser();
        String reason = request == null ? null : request.reason();
        return meetingService.cancel(id, reason, actor);
    }

    /** Mark an owned meeting as a no-show ("didn't happen"), optionally with a note (Req 9.2). */
    @PostMapping("/{id}/no-show")
    public MeetingResponse markNoShow(@PathVariable Long id,
                                      @Valid @RequestBody(required = false) MeetingNoShowRequest request) {
        AuthPrincipal actor = currentUserService.requireCurrentUser();
        String note = request == null ? null : request.note();
        return meetingService.markNoShow(id, note, actor);
    }

    /** Record an outcome for an owned meeting, marking it DONE (Req 9.2). */
    @PostMapping("/{id}/outcome")
    public MeetingResponse recordOutcome(@PathVariable Long id,
                                         @Valid @RequestBody MeetingOutcomeRequest request) {
        AuthPrincipal actor = currentUserService.requireCurrentUser();
        return meetingService.recordOutcome(id, request, actor);
    }
}
