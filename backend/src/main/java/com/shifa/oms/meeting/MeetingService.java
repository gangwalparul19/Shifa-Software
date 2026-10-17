package com.shifa.oms.meeting;

import com.shifa.oms.activity.ActivityService;
import com.shifa.oms.activity.ActivityTypes;
import com.shifa.oms.auth.AuthPrincipal;
import com.shifa.oms.auth.Role;
import com.shifa.oms.auth.SalespersonScopeResolver;
import com.shifa.oms.common.ResourceNotFoundException;
import com.shifa.oms.common.ValidationException;
import com.shifa.oms.meeting.dto.MeetingOutcomeRequest;
import com.shifa.oms.meeting.dto.MeetingResponse;
import com.shifa.oms.meeting.dto.ScheduleMeetingRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Customer-meeting application service (CRM expansion Phase 2, Task 11, design
 * &sect;2.3). Schedule / reschedule / cancel / record-outcome, owner-scoped like
 * tasks (own / team / all via {@link SalespersonScopeResolver#creatorScope},
 * Req 9.4). Each lifecycle change records a {@link ActivityService} entry
 * (Req 9.2, 11.1); upcoming reminders are delivered by {@link MeetingReminderJob}.
 */
@Service
public class MeetingService {

    private final MeetingRepository meetingRepository;
    private final SalespersonScopeResolver scopeResolver;
    private final ActivityService activityService;

    public MeetingService(MeetingRepository meetingRepository,
                          SalespersonScopeResolver scopeResolver,
                          ActivityService activityService) {
        this.meetingRepository = Objects.requireNonNull(meetingRepository, "meetingRepository");
        this.scopeResolver = Objects.requireNonNull(scopeResolver, "scopeResolver");
        this.activityService = activityService;
    }

    /** Owner-scoped meeting list, optional status filter (Req 9.4). */
    @Transactional(readOnly = true)
    public List<MeetingResponse> list(MeetingStatus status, AuthPrincipal actor) {
        Objects.requireNonNull(actor, "actor");
        List<Long> ownerIds = scopeResolver.creatorScope(actor).orElse(null);
        return meetingRepository.findScoped(ownerIds, status).stream()
                .map(MeetingResponse::from)
                .toList();
    }

    /** Schedules a meeting (Req 9.1). */
    @Transactional
    public MeetingResponse schedule(ScheduleMeetingRequest request, AuthPrincipal actor) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(actor, "actor");
        validate(request);
        Long owner = resolveOwner(request.ownerUserId(), actor);
        MeetingEntity meeting = new MeetingEntity(owner, request.title().trim(), request.startAt());
        apply(meeting, request);
        MeetingEntity saved = meetingRepository.save(meeting);
        recordActivity(ActivityTypes.MEETING_SCHEDULED, saved, actor,
                "Meeting scheduled: " + saved.getTitle() + " @ " + saved.getStartAt());
        return MeetingResponse.from(saved);
    }

    /** Reschedules / edits an owned meeting (Req 9.2). */
    @Transactional
    public MeetingResponse reschedule(Long id, ScheduleMeetingRequest request, AuthPrincipal actor) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(actor, "actor");
        validate(request);
        MeetingEntity meeting = loadScoped(id, actor);
        meeting.setTitle(request.title().trim());
        meeting.setStartAt(request.startAt());
        apply(meeting, request);
        meeting.setStatus(MeetingStatus.SCHEDULED);
        meeting.setRemindedOn(null); // re-arm the reminder for the new start
        MeetingEntity saved = meetingRepository.save(meeting);
        recordActivity(ActivityTypes.MEETING_RESCHEDULED, saved, actor,
                "Meeting rescheduled: " + saved.getTitle() + " @ " + saved.getStartAt());
        return MeetingResponse.from(saved);
    }

    /** Cancels an owned meeting (Req 9.2). */
    @Transactional
    public MeetingResponse cancel(Long id, AuthPrincipal actor) {
        Objects.requireNonNull(actor, "actor");
        MeetingEntity meeting = loadScoped(id, actor);
        meeting.setStatus(MeetingStatus.CANCELLED);
        MeetingEntity saved = meetingRepository.save(meeting);
        recordActivity(ActivityTypes.MEETING_CANCELLED, saved, actor,
                "Meeting cancelled: " + saved.getTitle());
        return MeetingResponse.from(saved);
    }

    /** Records an outcome for an owned meeting, marking it DONE (Req 9.2). */
    @Transactional
    public MeetingResponse recordOutcome(Long id, MeetingOutcomeRequest request, AuthPrincipal actor) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(actor, "actor");
        if (request.outcome() == null || request.outcome().isBlank()) {
            throw new ValidationException("outcome is required.");
        }
        MeetingEntity meeting = loadScoped(id, actor);
        meeting.setOutcome(request.outcome().trim());
        meeting.setStatus(MeetingStatus.DONE);
        MeetingEntity saved = meetingRepository.save(meeting);
        recordActivity(ActivityTypes.MEETING_OUTCOME, saved, actor,
                "Meeting outcome: " + saved.getTitle(), request.outcome().trim());
        return MeetingResponse.from(saved);
    }

    // --- Internal helpers ---------------------------------------------------

    private static void validate(ScheduleMeetingRequest request) {
        if (request.title() == null || request.title().isBlank()) {
            throw new ValidationException("title is required.");
        }
        if (request.startAt() == null) {
            throw new ValidationException("startAt is required.");
        }
    }

    private static void apply(MeetingEntity meeting, ScheduleMeetingRequest request) {
        meeting.setLeadId(request.leadId());
        meeting.setCustomerMobile(blankToNull(request.customerMobile()));
        meeting.setMode(request.mode());
        meeting.setLocation(blankToNull(request.location()));
        meeting.setNotes(blankToNull(request.notes()));
    }

    private Long resolveOwner(Long requestedOwner, AuthPrincipal actor) {
        if (requestedOwner == null || requestedOwner.equals(actor.userId())) {
            return actor.userId();
        }
        if (actor.role() != Role.ADMIN && actor.role() != Role.TEAM_LEAD) {
            throw new AccessDeniedException("You may only schedule meetings for yourself.");
        }
        if (actor.role() == Role.TEAM_LEAD) {
            List<Long> scope = scopeResolver.creatorScope(actor).orElse(List.of());
            if (!scope.contains(requestedOwner)) {
                throw new AccessDeniedException("You may only schedule meetings for your team.");
            }
        }
        return requestedOwner;
    }

    private MeetingEntity loadScoped(Long id, AuthPrincipal actor) {
        MeetingEntity meeting = meetingRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Meeting " + id + " does not exist."));
        Optional<List<Long>> scope = scopeResolver.creatorScope(actor);
        if (scope.isPresent() && !scope.get().contains(meeting.getOwnerUserId())) {
            throw new ResourceNotFoundException("Meeting " + id + " does not exist.");
        }
        return meeting;
    }

    private void recordActivity(String type, MeetingEntity m, AuthPrincipal actor, String summary) {
        recordActivity(type, m, actor, summary, null);
    }

    private void recordActivity(String type, MeetingEntity m, AuthPrincipal actor,
                                String summary, String detail) {
        if (activityService == null || m.getLeadId() == null && m.getCustomerMobile() == null) {
            return;
        }
        activityService.record(type, m.getLeadId(), m.getCustomerMobile(), actor, summary, detail);
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
