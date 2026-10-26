package com.shifa.oms.lead;

import com.shifa.oms.activity.ActivityService;
import com.shifa.oms.activity.ActivityTypes;
import com.shifa.oms.adminnotification.StaffNotificationDispatcher;
import com.shifa.oms.audit.AuditActions;
import com.shifa.oms.audit.AuditService;
import com.shifa.oms.auth.AuthPrincipal;
import com.shifa.oms.auth.Role;
import com.shifa.oms.auth.SalespersonScopeResolver;
import com.shifa.oms.auth.User;
import com.shifa.oms.auth.UserRepository;
import com.shifa.oms.common.IllegalLeadTransitionException;
import com.shifa.oms.common.ResourceNotFoundException;
import com.shifa.oms.common.ValidationException;
import com.shifa.oms.crmsettings.CrmSettingsService;
import com.shifa.oms.lead.dto.BulkAssignResult;
import com.shifa.oms.lead.dto.CreateLeadRequest;
import com.shifa.oms.lead.dto.LeadConvertRequest;
import com.shifa.oms.lead.dto.LeadReports.BySourceReport;
import com.shifa.oms.lead.dto.LeadReports.ConversionReport;
import com.shifa.oms.lead.dto.LeadReports.LostReasonReport;
import com.shifa.oms.lead.dto.LeadReports.PipelineReport;
import com.shifa.oms.lead.dto.LeadResponse;
import com.shifa.oms.lead.dto.LeadSummaryResponse;
import com.shifa.oms.lead.dto.LeadTagResponse;
import com.shifa.oms.order.LeadSource;
import com.shifa.oms.order.OrderEntity;
import com.shifa.oms.order.OrderRepository;
import com.shifa.oms.order.OrderService;
import com.shifa.oms.order.dto.AssignableCreatorResponse;
import com.shifa.oms.order.dto.CreateOrderRequest;
import com.shifa.oms.order.dto.OrderResponse;
import com.shifa.oms.permission.PermissionService;
import com.shifa.oms.pipeline.Pipeline;
import com.shifa.oms.pipeline.PipelineService;
import com.shifa.oms.pipeline.PipelineStage;
import com.shifa.oms.reporting.domain.DateRange;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Lead / sales-pipeline application service (design &sect;Components,
 * &sect;State Machine). The single entry point for lead operations — capture,
 * status transition, set follow-up, and the scoped read views — keeping
 * validation, legality (via {@link LeadStatus}), salesperson scoping (via
 * {@link SalespersonScopeResolver}), status history, and audit in one place,
 * the same pattern as the order module's services.
 *
 * <p>Every accepted mutation appends exactly one {@link LeadStatusHistory} row to
 * the aggregate (persisted atomically via the cascaded {@code @OneToMany}) and
 * records one audit event (Req 2.6, 7.5). Reads are salesperson-scoped: a
 * salesperson sees only their own leads and an out-of-scope id is a 404
 * (Req 3.1, 3.2), mirroring order scoping.
 *
 * <p>{@link LeadStatus#WON} is never a manual transition target here — it is
 * reachable only through the Convert action (Req 2.5, 4.2), a later task.
 */
@Service
public class LeadService {

    /** Matches a valid 10-digit customer mobile (Req 1.3). */
    private static final Pattern MOBILE = Pattern.compile("\\d{10}");

    /** Maximum length of the optional {@code OTHER} source note (Req 1.2). */
    private static final int MAX_SOURCE_NOTE = 200;
    /** Maximum length of the optional lost-reason note. */
    private static final int MAX_LOST_REASON_NOTE = 200;
    /** Maximum length of the free-text working note (Req 1.4). */
    private static final int MAX_NOTE = 1000;

    /** Pure, stateless lead-report aggregator (design &sect;Reporting). */
    private static final LeadReportAggregator REPORTS = new LeadReportAggregator();

    /** The default follow-up time-of-day when none is given (Req 6.1); 09:00 IST. */
    private static final LocalTime DEFAULT_FOLLOW_UP_TIME = LocalTime.of(9, 0);

    private final LeadRepository leadRepository;
    private final SalespersonScopeResolver scopeResolver;
    private final AuditService auditService;
    private final OrderService orderService;
    private final LeadTagRepository tagRepository;
    private final LeadTagAssignmentRepository tagAssignmentRepository;
    private final UserRepository userRepository;
    private final StaffNotificationDispatcher notificationDispatcher;
    private final ActivityService activityService;
    private final OrderRepository orderRepository;
    private final PipelineService pipelineService;
    private final CrmSettingsService crmSettingsService;
    private final PermissionService permissionService;
    private final Clock clock;

    /**
     * Production constructor (Spring): uses the system default-zone clock for
     * "today" (Req: business runs entirely in IST — the app pins the JVM default
     * zone to Asia/Kolkata at startup, {@code Application.main}). Was
     * {@code Clock.systemUTC()}, which is immune to that pin and would compute
     * "today"/due-follow-ups a fixed 5.5h off from the IST business day.
     */
    @Autowired
    public LeadService(LeadRepository leadRepository,
                       SalespersonScopeResolver scopeResolver,
                       AuditService auditService,
                       OrderService orderService,
                       LeadTagRepository tagRepository,
                       LeadTagAssignmentRepository tagAssignmentRepository,
                       UserRepository userRepository,
                       StaffNotificationDispatcher notificationDispatcher,
                       ActivityService activityService,
                       OrderRepository orderRepository,
                       PipelineService pipelineService,
                       CrmSettingsService crmSettingsService,
                       PermissionService permissionService) {
        this(leadRepository, scopeResolver, auditService, orderService, tagRepository,
                tagAssignmentRepository, userRepository, notificationDispatcher, activityService,
                orderRepository, pipelineService, crmSettingsService, permissionService,
                Clock.systemDefaultZone());
    }

    /**
     * Test constructor with an injected clock (deterministic due-follow-up
     * "today"). The CRM-expansion collaborators ({@code tagRepository},
     * {@code tagAssignmentRepository}, {@code userRepository},
     * {@code notificationDispatcher}) are nullable — the tag/assign features are
     * no-ops when a dependency is absent, so tests that only exercise the shipped
     * capture/transition/report flows can pass {@code null} for them. The CRM
     * expansion Phase 3 {@code pipelineService} is likewise nullable — when null
     * the pipeline/stage overlay is simply not maintained and the authoritative
     * {@link LeadStatus} engine behaves exactly as today.
     */
    public LeadService(LeadRepository leadRepository,
                       SalespersonScopeResolver scopeResolver,
                       AuditService auditService,
                       OrderService orderService,
                       LeadTagRepository tagRepository,
                       LeadTagAssignmentRepository tagAssignmentRepository,
                       UserRepository userRepository,
                       StaffNotificationDispatcher notificationDispatcher,
                       ActivityService activityService,
                       OrderRepository orderRepository,
                       PipelineService pipelineService,
                       CrmSettingsService crmSettingsService,
                       PermissionService permissionService,
                       Clock clock) {
        this.leadRepository = Objects.requireNonNull(leadRepository, "leadRepository");
        this.scopeResolver = Objects.requireNonNull(scopeResolver, "scopeResolver");
        this.auditService = Objects.requireNonNull(auditService, "auditService");
        this.orderService = Objects.requireNonNull(orderService, "orderService");
        this.tagRepository = tagRepository;
        this.tagAssignmentRepository = tagAssignmentRepository;
        this.userRepository = userRepository;
        this.notificationDispatcher = notificationDispatcher;
        this.activityService = activityService;
        this.orderRepository = orderRepository;
        this.pipelineService = pipelineService;
        this.crmSettingsService = crmSettingsService;
        this.permissionService = permissionService;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Records a lead activity when the (nullable) activity log is configured. */
    private void recordActivity(String type, LeadEntity lead, AuthPrincipal actor,
                                String summary, String detail) {
        if (activityService == null || lead == null) {
            return;
        }
        activityService.record(type, lead.getId(), lead.getCustomerMobile(), actor, summary, detail);
    }

    // --- Capture (Req 1) ----------------------------------------------------

    /**
     * Captures a new lead (Req 1.1-1.6). Requires a non-blank name and an in-set
     * {@link LeadSource} (with an {@code OTHER} note ≤200); the mobile is optional
     * but validated as 10 digits when present. The owner is the acting user, the
     * status starts {@link LeadStatus#NEW}, and exactly one creation history row
     * (from = null) is appended (Req 1.5, 2.1). Invalid input is rejected with a
     * 400 and nothing is persisted (Req 1.6).
     */
    @Transactional
    public LeadResponse capture(CreateLeadRequest request, AuthPrincipal actor) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(actor, "actor");

        String name = request.customerName();
        if (name == null || name.isBlank()) {
            throw new ValidationException("customerName is required.");
        }
        LeadSource source = request.leadSource();
        if (source == null) {
            throw new ValidationException(
                    "leadSource is required and must be one of the defined lead sources.");
        }
        validateLength(request.leadSourceNote(), MAX_SOURCE_NOTE, "leadSourceNote");
        validateLength(request.note(), MAX_NOTE, "note");
        String mobile = blankToNull(request.customerMobile());
        if (mobile != null && !MOBILE.matcher(mobile).matches()) {
            throw new ValidationException("customerMobile must be exactly 10 digits.");
        }

        LeadEntity lead = new LeadEntity(name.trim(), source, actor.userId());
        lead.setCustomerMobile(mobile);
        lead.setCustomerEmail(blankToNull(request.customerEmail()));
        lead.setLeadSourceNote(blankToNull(request.leadSourceNote()));
        lead.setNote(blankToNull(request.note()));
        lead.setFollowUpDate(request.followUpDate());
        lead.setFollowUpTime(resolveFollowUpTime(request.followUpDate(), request.followUpTime()));
        // CRM expansion Req 7: optional shipping address (pre-fills conversion).
        lead.setAddressLine(blankToNull(request.addressLine()));
        lead.setCity(blankToNull(request.city()));
        lead.setState(blankToNull(request.state()));
        lead.setPostalCode(blankToNull(request.postalCode()));
        // CRM expansion: track the creator + the "Lead by Admin" marker (Req 3.1).
        lead.setCreatedByUserId(actor.userId());
        lead.setCreatedByAdmin(actor.role() == Role.ADMIN);
        // Creation history row: null from-status → initial NEW status (Req 2.1, 7.5).
        lead.addStatusHistory(new LeadStatusHistory(null, LeadStatus.INITIAL, actor.username()));
        // CRM expansion Phase 3: place the lead on the Default pipeline's entry stage
        // (overlay kept in sync with the authoritative NEW status; no-op if unseeded).
        assignDefaultPipelineEntry(lead);

        LeadEntity saved = leadRepository.save(lead);
        auditService.record(AuditActions.LEAD_CAPTURED, AuditActions.ENTITY_LEAD, idOf(saved),
                "Lead captured for " + name.trim() + " via " + source);
        return LeadResponse.from(saved, tagsOf(saved.getId()));
    }

    // --- Transition (Req 2) -------------------------------------------------

    /**
     * Advances a lead's status or marks it LOST (Req 2.2-2.7). Loads the lead
     * scoped to the actor; enforces legality through {@link LeadStatus} (an
     * illegal edge or a change on a terminal lead is a 409, leaving the lead
     * unchanged); rejects {@link LeadStatus#WON} (Convert-only, Req 2.5); requires
     * a {@link LostReason} when moving to {@link LeadStatus#LOST} and persists it
     * (Req 2.3). Appends exactly one history row and records one audit event.
     */
    @Transactional
    public LeadResponse transition(Long id, LeadStatus toStatus, LostReason lostReason,
                                   String lostReasonNote, AuthPrincipal actor) {
        Objects.requireNonNull(toStatus, "toStatus");
        Objects.requireNonNull(actor, "actor");

        LeadEntity lead = loadScoped(id, actor);
        LeadStatus from = lead.getStatus();

        // WON is set only via Convert — never a manual target (Req 2.5, 4.2).
        if (toStatus == LeadStatus.WON) {
            throw new IllegalLeadTransitionException(
                    "WON is set only by converting a lead, not by a manual status change.");
        }
        // Legality (also rejects any change on a terminal lead) → 409, no-op (Req 2.4, 2.7).
        if (!LeadStatus.canTransitionTo(from, toStatus)) {
            String reason = LeadStatus.isTerminal(from)
                    ? "Lead " + id + " is terminal (" + from + ") and cannot change status."
                    : "Lead " + id + " cannot transition from " + from + " to " + toStatus + ".";
            throw new IllegalLeadTransitionException(reason);
        }

        // Marking LOST requires a valid categorized reason (Req 2.3).
        if (toStatus == LeadStatus.LOST) {
            if (lostReason == null) {
                throw new ValidationException("lostReason is required when marking a lead LOST.");
            }
            validateLength(lostReasonNote, MAX_LOST_REASON_NOTE, "lostReasonNote");
            lead.setLostReason(lostReason);
            lead.setLostReasonNote(blankToNull(lostReasonNote));
        }

        lead.setStatus(toStatus);
        lead.addStatusHistory(new LeadStatusHistory(from, toStatus, actor.username()));
        // CRM expansion Phase 3: keep the Default-pipeline stage overlay in sync
        // with the new authoritative status (no-op if unseeded / not Default).
        syncDefaultStage(lead, toStatus);

        LeadEntity saved = leadRepository.save(lead);
        auditService.record(AuditActions.LEAD_STATUS_CHANGED, AuditActions.ENTITY_LEAD, idOf(saved),
                "Lead " + id + " " + from + " \u2192 " + toStatus + " by " + actor.username());
        recordActivity(ActivityTypes.STAGE_CHANGE, saved, actor,
                "Stage " + from + " \u2192 " + toStatus,
                toStatus == LeadStatus.LOST && lostReason != null ? "Lost: " + lostReason : null);
        return LeadResponse.from(saved, tagsOf(saved.getId()));
    }

    // --- Follow-up (Req 5.1, CRM expansion Req 6) ---------------------------

    /**
     * Sets or clears a lead's follow-up date and optional time (Req 5.1, 5.5; CRM
     * expansion Req 6). A {@code null} date clears both; setting/updating the date
     * re-arms the reminder by clearing the "last reminded" marker so the lead can
     * be reminded again on its new due moment. A {@code null} time with a non-null
     * date falls back to the configured default time-of-day (Req 6.1), preserving
     * today's date-only behaviour.
     */
    @Transactional
    public LeadResponse setFollowUp(Long id, LocalDate followUpDate, LocalTime followUpTime,
                                    AuthPrincipal actor) {
        Objects.requireNonNull(actor, "actor");
        LeadEntity lead = loadScoped(id, actor);
        lead.setFollowUpDate(followUpDate);
        lead.setFollowUpTime(resolveFollowUpTime(followUpDate, followUpTime));
        lead.setRemindedOn(null);
        lead.setFollowUpMissedAt(null); // rescheduling clears any prior Missed state (Req 10)
        LeadEntity saved = leadRepository.save(lead);
        auditService.record(AuditActions.LEAD_FOLLOW_UP_SET, AuditActions.ENTITY_LEAD, idOf(saved),
                followUpDate == null
                        ? "Lead " + id + " follow-up cleared"
                        : "Lead " + id + " follow-up set to " + followUpDate
                                + (saved.getFollowUpTime() != null ? " " + saved.getFollowUpTime() : ""));
        recordActivity(ActivityTypes.FOLLOW_UP_SET, saved, actor,
                followUpDate == null ? "Follow-up cleared"
                        : "Follow-up set to " + followUpDate
                                + (saved.getFollowUpTime() != null ? " " + saved.getFollowUpTime() : ""),
                null);
        return LeadResponse.from(saved, tagsOf(saved.getId()));
    }

    // --- Edit (Req 3.6) -----------------------------------------------------

    /**
     * Edits a non-terminal lead's capture fields (design &sect;API: name / mobile
     * / email / note / source / follow-up). Loads the lead scoped to the actor;
     * a terminal lead (WON/LOST) rejects edits with a 409 (its record is frozen).
     * Validation mirrors {@link #capture}. This does not change the lead status,
     * so no history row is appended; one audit event is recorded.
     */
    @Transactional
    public LeadResponse edit(Long id, CreateLeadRequest request, AuthPrincipal actor) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(actor, "actor");

        LeadEntity lead = loadScoped(id, actor);
        if (LeadStatus.isTerminal(lead.getStatus())) {
            throw new IllegalLeadTransitionException(
                    "Lead " + id + " is terminal (" + lead.getStatus() + ") and cannot be edited.");
        }

        String name = request.customerName();
        if (name == null || name.isBlank()) {
            throw new ValidationException("customerName is required.");
        }
        LeadSource source = request.leadSource();
        if (source == null) {
            throw new ValidationException(
                    "leadSource is required and must be one of the defined lead sources.");
        }
        validateLength(request.leadSourceNote(), MAX_SOURCE_NOTE, "leadSourceNote");
        validateLength(request.note(), MAX_NOTE, "note");
        String mobile = blankToNull(request.customerMobile());
        if (mobile != null && !MOBILE.matcher(mobile).matches()) {
            throw new ValidationException("customerMobile must be exactly 10 digits.");
        }

        lead.setCustomerName(name.trim());
        lead.setLeadSource(source);
        lead.setLeadSourceNote(blankToNull(request.leadSourceNote()));
        lead.setCustomerMobile(mobile);
        lead.setCustomerEmail(blankToNull(request.customerEmail()));
        lead.setNote(blankToNull(request.note()));
        // CRM expansion Req 7: optional shipping address, editable while non-terminal.
        lead.setAddressLine(blankToNull(request.addressLine()));
        lead.setCity(blankToNull(request.city()));
        lead.setState(blankToNull(request.state()));
        lead.setPostalCode(blankToNull(request.postalCode()));
        if (request.followUpDate() != null && !request.followUpDate().equals(lead.getFollowUpDate())) {
            // Re-arm the reminder when the follow-up date changes (mirrors setFollowUp).
            lead.setRemindedOn(null);
            lead.setFollowUpMissedAt(null);
        }
        lead.setFollowUpDate(request.followUpDate());
        lead.setFollowUpTime(resolveFollowUpTime(request.followUpDate(), request.followUpTime()));

        LeadEntity saved = leadRepository.save(lead);
        auditService.record(AuditActions.LEAD_STATUS_CHANGED, AuditActions.ENTITY_LEAD, idOf(saved),
                "Lead " + id + " capture fields edited by " + actor.username());
        return LeadResponse.from(saved, tagsOf(saved.getId()));
    }

    // --- Convert (Req 4) ----------------------------------------------------

    /**
     * Converts a lead into an order and marks it {@link LeadStatus#WON} (Req
     * 4.1-4.6, design &sect;Convert Flow). Loads the lead scoped to the actor and
     * rejects a terminal lead with a 409 (Req 4.6). Builds a
     * {@link CreateOrderRequest} that <strong>forces</strong> the customer
     * identity and lead source from the lead (the client only supplies the
     * shipping address, line items, and payment) so the created order always
     * carries the lead's origin channel (Req 4.2, 4.3). The order is created via
     * {@link OrderService#createSalespersonOrder} in the <em>same</em>
     * transaction; on success the lead is set {@code WON} with its
     * {@code converted_order_id} linked, a history row appended, and a
     * {@code LEAD_CONVERTED} audit recorded.
     *
     * <p>If order creation throws (validation / insufficient stock), the
     * exception propagates and this transaction rolls back — the lead is left
     * unchanged and no order exists (Req 4.4).
     */
    @Transactional
    public LeadResponse convert(Long id, LeadConvertRequest request, AuthPrincipal actor) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(actor, "actor");

        LeadEntity lead = loadScoped(id, actor);
        LeadStatus from = lead.getStatus();
        if (LeadStatus.isTerminal(from)) {
            throw new IllegalLeadTransitionException(
                    "Lead " + id + " is terminal (" + from + ") and cannot be converted.");
        }

        // Force customer + lead source from the lead; the client only supplies the
        // order's address / line items / payment (Req 4.2, 4.3, design §Convert Flow).
        CreateOrderRequest orderRequest = new CreateOrderRequest(
                lead.getCustomerName(),
                lead.getCustomerMobile(),
                request.addressLine(),
                request.city(),
                request.state(),
                request.postalCode(),
                request.items(),
                request.amountReceived(),
                request.paymentScreenshotKey(),
                lead.getLeadSource(),
                lead.getLeadSourceNote(),
                lead.getCustomerEmail(),
                request.notes(),
                null,
                request.discountType(),
                request.discountValue(),
                null,
                request.deliveryMethod(),
                request.paymentScreenshotKeys(),
                null, // onBehalfOfUserId: convert is never an on-behalf flow
                null, // country: lead conversion is always a domestic order
                request.paymentMethod(),
                request.paymentAccountId(),
                request.paymentUtr());

        // Same transaction: a failure here rolls the whole convert back (Req 4.4).
        OrderResponse order = orderService.createSalespersonOrder(orderRequest, actor);

        lead.setStatus(LeadStatus.WON);
        lead.setConvertedOrderId(order.id());
        lead.addStatusHistory(new LeadStatusHistory(from, LeadStatus.WON, actor.username()));
        syncDefaultStage(lead, LeadStatus.WON);

        LeadEntity saved = leadRepository.save(lead);
        auditService.record(AuditActions.LEAD_CONVERTED, AuditActions.ENTITY_LEAD, idOf(saved),
                "Lead " + id + " converted to order " + order.id() + " (" + order.orderCode()
                        + ") by " + actor.username());
        return LeadResponse.from(saved, tagsOf(saved.getId()));
    }

    // --- Assignment (CRM expansion Req 1) ----------------------------------

    /**
     * Assigns / reassigns a lead to {@code toUserId} (Req 1.2, 1.3, 1.5, 1.6).
     * Only an ADMIN or a TEAM_LEAD may assign — a SALESPERSON is rejected with a
     * 403 ({@link AccessDeniedException}). The lead's {@link LeadEntity#ownerUserId}
     * (assignee) and {@link LeadEntity#assignedAt} are updated while
     * {@link LeadEntity#createdByUserId} (the original creator) is preserved
     * (Req 1.1). The change is audited and the new assignee is notified (Req 1.6).
     *
     * <p>Team round-robin (Req 1.3): when {@code roundRobin} is true and
     * {@code toUserId} is a {@link Role#TEAM_LEAD}, the lead is distributed to the
     * team member with the fewest currently-open leads (tie-break by lowest user
     * id for determinism) rather than to the lead themselves; an empty team falls
     * back to the team lead. When {@code roundRobin} is false the lead is assigned
     * to {@code toUserId} directly (today's behaviour).
     */
    @Transactional
    public LeadResponse assign(Long id, Long toUserId, boolean roundRobin, AuthPrincipal actor) {
        Objects.requireNonNull(actor, "actor");
        requireAssigner(actor);
        if (toUserId == null) {
            throw new ValidationException("toUserId is required.");
        }
        LeadEntity lead = leadRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Lead " + id + " does not exist."));
        Long resolved = resolveAssignee(toUserId, roundRobin);
        reassign(lead, resolved, actor);
        LeadEntity saved = leadRepository.save(lead);
        return LeadResponse.from(saved, tagsOf(saved.getId()));
    }

    /**
     * Resolves the actual assignee for a single assign (Req 1.3). With
     * round-robin off, or when the team round-robin cannot be resolved (no
     * {@code UserRepository}, {@code toUserId} is not a team lead, or the team is
     * empty), the given {@code toUserId} is used directly. Otherwise the
     * least-loaded team member (fewest open leads, tie-break by id) is chosen.
     */
    private Long resolveAssignee(Long toUserId, boolean roundRobin) {
        if (!roundRobin || userRepository == null) {
            return toUserId;
        }
        User target = userRepository.findById(toUserId).orElse(null);
        if (target == null || target.getRole() != Role.TEAM_LEAD) {
            return toUserId; // only a team lead fans out round-robin
        }
        List<Long> memberIds = userRepository.findByTeamLeadIdOrderByFullNameAsc(toUserId).stream()
                .filter(User::isActive)
                .map(User::getId)
                .toList();
        if (memberIds.isEmpty()) {
            return toUserId; // no members → keep the lead with the team lead
        }
        Map<Long, Long> openByOwner = new LinkedHashMap<>();
        for (LeadRepository.OpenLeadCount row : leadRepository.countOpenLeadsByOwner(memberIds)) {
            openByOwner.put(row.getOwnerId(), row.getCnt());
        }
        return chooseRoundRobinMember(memberIds, openByOwner);
    }

    /**
     * Pure round-robin member selection (CRM expansion, Req 1.3): returns the
     * member id with the fewest currently-open leads, breaking ties by the lowest
     * user id so the choice is deterministic. {@code openCountByOwner} maps an
     * owner id to its open-lead count; a member absent from the map counts as 0.
     * Expects a non-empty {@code memberIds}.
     */
    static Long chooseRoundRobinMember(List<Long> memberIds, Map<Long, Long> openCountByOwner) {
        Long best = null;
        long bestCount = Long.MAX_VALUE;
        for (Long memberId : memberIds) {
            if (memberId == null) {
                continue;
            }
            long open = openCountByOwner == null ? 0L
                    : openCountByOwner.getOrDefault(memberId, 0L);
            if (open < bestCount || (open == bestCount && (best == null || memberId < best))) {
                best = memberId;
                bestCount = open;
            }
        }
        return best;
    }

    /**
     * Bulk reassigns the selected leads to {@code toUserId} in a single action,
     * reporting per-lead success/failure (Req 1.4). ADMIN/TEAM_LEAD only. A lead
     * that does not exist is skipped with a reason; the rest still reassign.
     */
    @Transactional
    public BulkAssignResult bulkAssign(List<Long> ids, Long toUserId, AuthPrincipal actor) {
        Objects.requireNonNull(actor, "actor");
        requireAssigner(actor);
        if (toUserId == null) {
            throw new ValidationException("toUserId is required.");
        }
        BulkAssignResult.Builder result = new BulkAssignResult.Builder();
        if (ids == null || ids.isEmpty()) {
            return result.build();
        }
        for (Long id : ids) {
            if (id == null) {
                continue;
            }
            Optional<LeadEntity> found = leadRepository.findById(id);
            if (found.isEmpty()) {
                result.skipped(id, "Lead does not exist.");
                continue;
            }
            LeadEntity lead = found.get();
            reassign(lead, toUserId, actor);
            leadRepository.save(lead);
            result.succeeded(id);
        }
        return result.build();
    }

    /** Applies the reassignment to a loaded lead: set owner + assigned_at, audit, notify. */
    private void reassign(LeadEntity lead, Long toUserId, AuthPrincipal actor) {
        Long from = lead.getOwnerUserId();
        lead.setOwnerUserId(toUserId);
        lead.setAssignedAt(LocalDateTime.now(clock));
        auditService.record(AuditActions.LEAD_ASSIGNED, AuditActions.ENTITY_LEAD, idOf(lead),
                "Lead " + lead.getId() + " assigned from " + from + " to " + toUserId
                        + " by " + actor.username());
        recordActivity(ActivityTypes.ASSIGNED, lead, actor,
                "Assigned to user " + toUserId, "from " + from + " by " + actor.username());
        if (notificationDispatcher != null && !Objects.equals(from, toUserId)) {
            notificationDispatcher.dispatchToUser(
                    "LEAD_ASSIGNED",
                    "New lead assigned",
                    "Lead \"" + lead.getCustomerName() + "\" was assigned to you.",
                    "info", null, null, null, toUserId);
        }
    }

    /**
     * The users the caller may assign leads to (CRM expansion, Req 1), backing
     * the assignee picker so it works for a TEAM_LEAD (whose scope is just their
     * own team) as well as an ADMIN. ADMIN sees all active team leads + active
     * salespeople (team leads first, then salespeople); a TEAM_LEAD sees their
     * own active team members plus themselves. Returns empty when the staff
     * directory is unavailable. ADMIN/TEAM_LEAD only.
     */
    @Transactional(readOnly = true)
    public List<AssignableCreatorResponse> assignableUsers(AuthPrincipal actor) {
        Objects.requireNonNull(actor, "actor");
        requireAssigner(actor);
        if (userRepository == null) {
            return List.of();
        }
        if (actor.role() == Role.TEAM_LEAD) {
            List<AssignableCreatorResponse> result = new ArrayList<>();
            userRepository.findById(actor.userId())
                    .filter(User::isActive)
                    .ifPresent(self -> result.add(AssignableCreatorResponse.from(self)));
            userRepository.findByTeamLeadIdOrderByFullNameAsc(actor.userId()).stream()
                    .filter(User::isActive)
                    .map(AssignableCreatorResponse::from)
                    .forEach(result::add);
            return result;
        }
        // ADMIN: all active team leads first, then all active salespeople.
        List<User> users = new ArrayList<>();
        users.addAll(userRepository.findByRoleOrderByCreatedAtDescIdDesc(Role.TEAM_LEAD));
        users.addAll(userRepository.findByRoleOrderByCreatedAtDescIdDesc(Role.SALESPERSON));
        return users.stream()
                .filter(User::isActive)
                .map(AssignableCreatorResponse::from)
                .toList();
    }

    /**
     * Only ADMIN / TEAM_LEAD may assign leads by default (Req 1.5): a SALESPERSON
     * gets 403. CRM expansion Phase 3 (Req 19): a user explicitly granted the
     * {@code ASSIGN_LEADS} permission also passes — the permission layer only ever
     * <em>widens</em> this, and with the safe-default seed (ADMIN+TEAM_LEAD) it
     * resolves to exactly today's role gate until an admin customizes it.
     */
    private void requireAssigner(AuthPrincipal actor) {
        if (actor.role() == Role.ADMIN || actor.role() == Role.TEAM_LEAD) {
            return;
        }
        if (permissionService != null
                && permissionService.has(actor, PermissionService.ASSIGN_LEADS)) {
            return;
        }
        throw new AccessDeniedException("Only an admin or team lead may assign leads.");
    }

    // --- Tags & call dispositions (CRM expansion Req 4, 5.1) ----------------

    /** The active managed tag vocabulary, in display order (Req 4). */
    @Transactional(readOnly = true)
    public List<LeadTagResponse> listTags() {
        if (tagRepository == null) {
            return List.of();
        }
        return tagRepository.findByActiveTrueOrderBySortOrderAscIdAsc().stream()
                .map(LeadTagResponse::from)
                .toList();
    }

    /**
     * Adds a tag to a lead, idempotently (Req 4.1, 4.4). Allowed on terminal
     * WON/LOST leads too (Req 5.1) — tagging never loads/mutates the lead
     * aggregate, only the join row. Salesperson-scoped: a salesperson may tag only
     * their own lead (404 otherwise). Audited.
     */
    @Transactional
    public LeadResponse addTag(Long leadId, Long tagId, AuthPrincipal actor) {
        Objects.requireNonNull(actor, "actor");
        requireTagSupport();
        LeadEntity lead = loadScoped(leadId, actor);
        LeadTag tag = tagRepository.findById(tagId)
                .orElseThrow(() -> new ResourceNotFoundException("Tag " + tagId + " does not exist."));
        if (!tagAssignmentRepository.existsByLeadIdAndTagId(leadId, tagId)) {
            tagAssignmentRepository.save(new LeadTagAssignment(leadId, tagId, actor.userId()));
            auditService.record(AuditActions.LEAD_TAG_ADDED, AuditActions.ENTITY_LEAD, idOf(lead),
                    "Lead " + leadId + " tagged \"" + tag.getName() + "\" by " + actor.username());
            recordActivity(ActivityTypes.TAG_ADDED, lead, actor,
                    "Tagged \"" + tag.getName() + "\"", null);
        }
        return LeadResponse.from(lead, tagsOf(leadId));
    }

    /**
     * Removes a tag from a lead (Req 4.4, 5.1). Allowed on terminal leads too.
     * Salesperson-scoped. Audited only when a row was actually removed.
     */
    @Transactional
    public LeadResponse removeTag(Long leadId, Long tagId, AuthPrincipal actor) {
        Objects.requireNonNull(actor, "actor");
        requireTagSupport();
        LeadEntity lead = loadScoped(leadId, actor);
        int removed = tagAssignmentRepository.deleteByLeadIdAndTagId(leadId, tagId);
        if (removed > 0) {
            auditService.record(AuditActions.LEAD_TAG_REMOVED, AuditActions.ENTITY_LEAD, idOf(lead),
                    "Lead " + leadId + " tag " + tagId + " removed by " + actor.username());
            recordActivity(ActivityTypes.TAG_REMOVED, lead, actor,
                    "Removed tag " + tagId, null);
        }
        return LeadResponse.from(lead, tagsOf(leadId));
    }

    private void requireTagSupport() {
        if (tagRepository == null || tagAssignmentRepository == null) {
            throw new IllegalStateException("Lead tag support is not configured.");
        }
    }

    // --- Scoped reads (Req 3) ----------------------------------------------

    /**
     * Role-scoped lead list with optional name/mobile search and status/source
     * filters (Req 3.1-3.4). A salesperson sees only their own leads; an admin
     * sees all.
     */
    @Transactional(readOnly = true)
    public List<LeadSummaryResponse> list(String q, LeadStatus status, LeadSource source,
                                          Boolean createdByAdmin, AuthPrincipal actor) {
        Long ownerUserId = scopeResolver.creatorConstraint(actor).orElse(null);
        String term = (q == null || q.isBlank()) ? null : q.trim();
        String statusName = status == null ? null : status.name();
        String sourceName = source == null ? null : source.name();
        List<LeadEntity> leads =
                leadRepository.search(ownerUserId, term, statusName, sourceName, createdByAdmin);
        Map<Long, List<String>> tagNames = tagNamesForLeads(leads);
        return leads.stream()
                .map(lead -> LeadSummaryResponse.from(
                        lead, tagNames.getOrDefault(lead.getId(), List.of())))
                .toList();
    }

    /** Scoped lead detail incl. status history + tags (Req 3.5); 404 when out of scope (Req 3.1). */
    @Transactional(readOnly = true)
    public LeadResponse detail(Long id, AuthPrincipal actor) {
        LeadEntity lead = loadScoped(id, actor);
        return LeadResponse.from(lead, tagsOf(lead.getId()));
    }

    /**
     * A lead's unified activity timeline (CRM expansion Phase 2, Task 9; Req
     * 11.2), newest-first. Enforces salesperson scoping via {@link #loadScoped}
     * (a salesperson may read only their own lead's timeline; out-of-scope =
     * 404). Returns empty when the activity log is unconfigured.
     */
    @Transactional(readOnly = true)
    public List<com.shifa.oms.activity.dto.ActivityResponse> leadActivity(Long id, AuthPrincipal actor) {
        loadScoped(id, actor); // authorise
        return activityService == null ? List.of() : activityService.timelineForLead(id);
    }

    /**
     * Active-lead pipeline counts per {@link LeadStatus} (Req 3.3, 6.3), scoped by
     * owner. The active stages (NEW/CONTACTED/QUOTED) are always present (0 when
     * empty) for a stable board; terminal statuses are excluded.
     */
    @Transactional(readOnly = true)
    public Map<LeadStatus, Long> pipelineCounts(AuthPrincipal actor) {
        Long ownerUserId = scopeResolver.creatorConstraint(actor).orElse(null);
        Map<LeadStatus, Long> counts = new EnumMap<>(LeadStatus.class);
        counts.put(LeadStatus.NEW, 0L);
        counts.put(LeadStatus.CONTACTED, 0L);
        counts.put(LeadStatus.QUOTED, 0L);
        for (LeadRepository.StatusCount row : leadRepository.pipelineCounts(ownerUserId)) {
            counts.put(LeadStatus.valueOf(row.getStatus()), row.getCount());
        }
        return counts;
    }

    /**
     * The acting user's due follow-ups: non-terminal leads whose follow-up date is
     * on or before today (Req 5.2, 5.4), scoped by owner and oldest-due first.
     */
    @Transactional(readOnly = true)
    public List<LeadSummaryResponse> dueFollowUps(AuthPrincipal actor) {
        Long ownerUserId = scopeResolver.creatorConstraint(actor).orElse(null);
        LocalDate today = LocalDate.now(clock);
        return leadRepository.findDueFollowUps(ownerUserId, today).stream()
                .map(LeadSummaryResponse::from)
                .toList();
    }

    /**
     * The acting user's missed follow-ups (CRM expansion Phase 2, Req 10.3):
     * non-terminal leads with {@code follow_up_missed_at} set, scoped by owner,
     * most-recently-missed first.
     */
    @Transactional(readOnly = true)
    public List<LeadSummaryResponse> missedFollowUps(AuthPrincipal actor) {
        Long ownerUserId = scopeResolver.creatorConstraint(actor).orElse(null);
        return leadRepository.findMissedFollowUps(ownerUserId).stream()
                .map(LeadSummaryResponse::from)
                .toList();
    }

    /**
     * The cross-user follow-up health monitor (CRM expansion Phase 2, Req 10.3):
     * upcoming / due / missed counts + the due and missed lists. Scoped by owner
     * (an admin sees all; a salesperson their own), so the same method backs both
     * the assignee view and the admin monitor.
     */
    @Transactional(readOnly = true)
    public com.shifa.oms.lead.dto.FollowUpHealthResponse followUpHealth(AuthPrincipal actor) {
        Long ownerUserId = scopeResolver.creatorConstraint(actor).orElse(null);
        LocalDate today = LocalDate.now(clock);
        List<LeadEntity> upcoming = leadRepository.findUpcomingFollowUps(ownerUserId, today);
        List<LeadEntity> missed = leadRepository.findMissedFollowUps(ownerUserId);
        // Due = due on/before today and not already marked missed.
        List<LeadEntity> due = leadRepository.findDueFollowUps(ownerUserId, today).stream()
                .filter(l -> l.getFollowUpMissedAt() == null)
                .toList();
        return new com.shifa.oms.lead.dto.FollowUpHealthResponse(
                upcoming.size(), due.size(), missed.size(),
                due.stream().map(LeadSummaryResponse::from).toList(),
                missed.stream().map(LeadSummaryResponse::from).toList());
    }

    // --- Reports (Req 6) ----------------------------------------------------

    /**
     * Leads-by-source report over an optional {@code [from, to]} window (Req 6.1),
     * salesperson-scoped (Req 6.5). Delegates the grouping to the pure
     * {@link LeadReportAggregator}.
     */
    @Transactional(readOnly = true)
    public BySourceReport reportBySource(LocalDate from, LocalDate to, AuthPrincipal actor) {
        return REPORTS.bySource(scopedRecords(actor), DateRange.of(from, to));
    }

    /**
     * Conversion report (per source and per owner) over an optional window
     * (Req 6.2), salesperson-scoped (Req 6.5).
     */
    @Transactional(readOnly = true)
    public ConversionReport reportConversion(LocalDate from, LocalDate to, AuthPrincipal actor) {
        return REPORTS.conversion(scopedRecords(actor), DateRange.of(from, to));
    }

    /** Pipeline snapshot: current active-lead counts per stage (Req 6.3), scoped. */
    @Transactional(readOnly = true)
    public PipelineReport reportPipeline(AuthPrincipal actor) {
        return REPORTS.pipeline(scopedRecords(actor));
    }

    /**
     * Lost-reasons report over an optional window (Req 6.4), salesperson-scoped
     * (Req 6.5).
     */
    @Transactional(readOnly = true)
    public LostReasonReport reportLostReasons(LocalDate from, LocalDate to, AuthPrincipal actor) {
        return REPORTS.lostReasons(scopedRecords(actor), DateRange.of(from, to));
    }

    /** The caller's visible leads (scoped) projected to pure report records. */
    private List<LeadReportRecord> scopedRecords(AuthPrincipal actor) {
        Long ownerUserId = scopeResolver.creatorConstraint(actor).orElse(null);
        return leadRepository.findAllScoped(ownerUserId).stream()
                .map(LeadReportRecord::from)
                .toList();
    }

    // --- Group reports (CRM expansion Phase 2, Req 13) ----------------------

    /** Leads grouped by stage over an optional window (Req 13.1), scoped. */
    @Transactional(readOnly = true)
    public com.shifa.oms.lead.dto.LeadReports.ByStageReport reportByStage(
            LocalDate from, LocalDate to, AuthPrincipal actor) {
        return REPORTS.byStage(scopedRecords(actor), DateRange.of(from, to));
    }

    /**
     * Leads grouped by assignee (user) with conversion + converted revenue over an
     * optional window (Req 13.2), scoped. Resolves owner display names + per-lead
     * converted revenue.
     */
    @Transactional(readOnly = true)
    public com.shifa.oms.lead.dto.LeadReports.GroupPerformanceReport reportByUser(
            LocalDate from, LocalDate to, AuthPrincipal actor) {
        List<LeadEntity> leads = scopedLeads(actor);
        List<LeadReportRecord> records = recordsWithRevenue(leads);
        Map<String, String> labels = ownerLabels(leads);
        return REPORTS.byUser(records, DateRange.of(from, to), labels);
    }

    /**
     * Leads grouped by team with conversion + converted revenue over an optional
     * window (Req 13.2), scoped. The team key is the assignee's team-lead id (via
     * {@code users.team_lead_id}); a team lead's own leads group under their own id.
     */
    @Transactional(readOnly = true)
    public com.shifa.oms.lead.dto.LeadReports.GroupPerformanceReport reportByTeam(
            LocalDate from, LocalDate to, AuthPrincipal actor) {
        List<LeadEntity> leads = scopedLeads(actor);
        List<LeadReportRecord> records = recordsWithRevenue(leads);
        Map<Long, String> ownerToTeam = ownerToTeamKey(leads);
        Map<String, String> labels = teamLabels(ownerToTeam.values());
        return REPORTS.byTeam(records, DateRange.of(from, to), ownerToTeam, labels);
    }

    private List<LeadEntity> scopedLeads(AuthPrincipal actor) {
        Long ownerUserId = scopeResolver.creatorConstraint(actor).orElse(null);
        return leadRepository.findAllScoped(ownerUserId);
    }

    /** Projects leads to records, resolving converted-order revenue in one batch. */
    private List<LeadReportRecord> recordsWithRevenue(List<LeadEntity> leads) {
        Map<Long, java.math.BigDecimal> revenueByOrderId = convertedOrderRevenue(leads);
        return leads.stream()
                .map(lead -> LeadReportRecord.from(lead,
                        lead.getConvertedOrderId() == null ? null
                                : revenueByOrderId.get(lead.getConvertedOrderId())))
                .toList();
    }

    /** Batch-loads the order totals for the converted orders referenced by the leads. */
    private Map<Long, java.math.BigDecimal> convertedOrderRevenue(List<LeadEntity> leads) {
        if (orderRepository == null) {
            return Map.of();
        }
        List<Long> orderIds = leads.stream()
                .map(LeadEntity::getConvertedOrderId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (orderIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, java.math.BigDecimal> byId = new LinkedHashMap<>();
        for (OrderEntity order : orderRepository.findAllById(orderIds)) {
            byId.put(order.getId(),
                    order.getTotalAmount() == null ? java.math.BigDecimal.ZERO : order.getTotalAmount());
        }
        return byId;
    }

    /** owner id (as string) → display name for the by-user report. */
    private Map<String, String> ownerLabels(List<LeadEntity> leads) {
        if (userRepository == null) {
            return Map.of();
        }
        List<Long> ownerIds = leads.stream()
                .map(LeadEntity::getOwnerUserId).filter(Objects::nonNull).distinct().toList();
        Map<String, String> labels = new LinkedHashMap<>();
        for (User u : userRepository.findAllById(ownerIds)) {
            labels.put(Long.toString(u.getId()),
                    u.getFullName() == null || u.getFullName().isBlank() ? u.getUsername() : u.getFullName());
        }
        return labels;
    }

    /** owner id → team key (team-lead id as string; a team lead maps to their own id). */
    private Map<Long, String> ownerToTeamKey(List<LeadEntity> leads) {
        if (userRepository == null) {
            return Map.of();
        }
        List<Long> ownerIds = leads.stream()
                .map(LeadEntity::getOwnerUserId).filter(Objects::nonNull).distinct().toList();
        Map<Long, String> map = new LinkedHashMap<>();
        for (User u : userRepository.findAllById(ownerIds)) {
            Long teamKey = u.getRole() == Role.TEAM_LEAD ? u.getId()
                    : (u.getTeamLeadId() != null ? u.getTeamLeadId() : null);
            if (teamKey != null) {
                map.put(u.getId(), Long.toString(teamKey));
            }
        }
        return map;
    }

    /** team key (team-lead id as string) → display name for the by-team report. */
    private Map<String, String> teamLabels(java.util.Collection<String> teamKeys) {
        if (userRepository == null) {
            return Map.of();
        }
        List<Long> ids = teamKeys.stream().distinct()
                .map(k -> {
                    try {
                        return Long.valueOf(k);
                    } catch (NumberFormatException e) {
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .toList();
        Map<String, String> labels = new LinkedHashMap<>();
        for (User u : userRepository.findAllById(ids)) {
            labels.put(Long.toString(u.getId()), "Team "
                    + (u.getFullName() == null || u.getFullName().isBlank() ? u.getUsername()
                            : u.getFullName()));
        }
        return labels;
    }

    /**
     * The pure due-follow-up membership predicate (Req 5.2, 5.4, design
     * &sect;Correctness Properties (8)): a lead is due iff it is non-terminal, has
     * a follow-up date, and that date is on or before {@code today}. The SQL
     * finder {@link LeadRepository#findDueFollowUps(Long, LocalDate)} implements
     * exactly this membership over the owner scope.
     */
    public static boolean isDueFollowUp(LeadStatus status, LocalDate followUpDate, LocalDate today) {
        return status != null
                && !LeadStatus.isTerminal(status)
                && followUpDate != null
                && !followUpDate.isAfter(today);
    }

    // --- Internal helpers ---------------------------------------------------

    /**
     * Places a freshly-captured lead on the Default pipeline's entry stage (CRM
     * expansion Phase 3). Pure overlay — the {@code status} column remains
     * authoritative; this only mirrors it into {@code pipeline_id}/{@code stage_id}.
     * A no-op when {@link PipelineService} is absent (unit tests) or the overlay is
     * unseeded, so today's enum-only behaviour is unchanged.
     */
    private void assignDefaultPipelineEntry(LeadEntity lead) {
        if (pipelineService == null) {
            return;
        }
        Optional<Pipeline> pipeline = pipelineService.defaultPipeline();
        if (pipeline.isEmpty()) {
            return;
        }
        lead.setPipelineId(pipeline.get().getId());
        pipelineService.defaultEntryStage().ifPresent(stage -> lead.setStageId(stage.getId()));
    }

    /**
     * Keeps a Default-pipeline lead's stage overlay in sync with its new status
     * (CRM expansion Phase 3). Only adjusts the stage when the lead is on the
     * Default pipeline (or has no pipeline yet, pre-overlay); a lead on a custom
     * pipeline is governed by its stage configuration and is left untouched here.
     * A no-op when {@link PipelineService} is absent or the overlay is unseeded.
     */
    private void syncDefaultStage(LeadEntity lead, LeadStatus status) {
        if (pipelineService == null || status == null) {
            return;
        }
        if (lead.getPipelineId() != null && !pipelineService.isDefaultPipeline(lead.getPipelineId())) {
            return; // custom pipeline — stage is governed by its own configuration
        }
        Optional<Pipeline> pipeline = pipelineService.defaultPipeline();
        if (pipeline.isEmpty()) {
            return;
        }
        lead.setPipelineId(pipeline.get().getId());
        Optional<PipelineStage> stage = pipelineService.defaultStageForStatus(status);
        stage.ifPresent(s -> lead.setStageId(s.getId()));
    }

    /**
     * Resolves the stored follow-up time (Req 6.1; CRM expansion Phase 3, Req
     * 18.3): a cleared date clears the time too; a non-null date with a null time
     * falls back to the admin-managed {@code crm_settings} default time-of-day
     * when configured, else the built-in {@link #DEFAULT_FOLLOW_UP_TIME} — so
     * existing date-only leads keep today's behaviour.
     */
    private LocalTime resolveFollowUpTime(LocalDate date, LocalTime time) {
        if (date == null) {
            return null;
        }
        if (time != null) {
            return time;
        }
        return configuredFollowUpDefaultTime();
    }

    /** The admin-managed default follow-up time-of-day, else the built-in default. */
    private LocalTime configuredFollowUpDefaultTime() {
        if (crmSettingsService != null) {
            try {
                LocalTime configured = crmSettingsService.followUpDefaultTime().orElse(null);
                if (configured != null) {
                    return configured;
                }
            } catch (RuntimeException e) {
                // Best-effort: fall back to the built-in default on any lookup failure.
            }
        }
        return DEFAULT_FOLLOW_UP_TIME;
    }

    /** The tag definitions currently applied to a lead (empty when unconfigured). */
    private List<LeadTagResponse> tagsOf(Long leadId) {
        if (leadId == null || tagRepository == null || tagAssignmentRepository == null) {
            return List.of();
        }
        List<LeadTagAssignment> assignments = tagAssignmentRepository.findByLeadId(leadId);
        if (assignments.isEmpty()) {
            return List.of();
        }
        Map<Long, LeadTag> byId = new LinkedHashMap<>();
        for (LeadTag tag : tagRepository.findAllById(
                assignments.stream().map(LeadTagAssignment::getTagId).toList())) {
            byId.put(tag.getId(), tag);
        }
        List<LeadTagResponse> tags = new ArrayList<>();
        for (LeadTagAssignment a : assignments) {
            LeadTag tag = byId.get(a.getTagId());
            if (tag != null) {
                tags.add(LeadTagResponse.from(tag));
            }
        }
        return tags;
    }

    /** Batch-loads the tag names per lead for a result list (one query, no N+1). */
    private Map<Long, List<String>> tagNamesForLeads(List<LeadEntity> leads) {
        if (leads.isEmpty() || tagRepository == null || tagAssignmentRepository == null) {
            return Map.of();
        }
        List<Long> leadIds = leads.stream().map(LeadEntity::getId).filter(Objects::nonNull).toList();
        if (leadIds.isEmpty()) {
            return Map.of();
        }
        List<LeadTagAssignment> assignments = tagAssignmentRepository.findByLeadIdIn(leadIds);
        if (assignments.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> nameById = new LinkedHashMap<>();
        for (LeadTag tag : tagRepository.findAllById(
                assignments.stream().map(LeadTagAssignment::getTagId).distinct().toList())) {
            nameById.put(tag.getId(), tag.getName());
        }
        Map<Long, List<String>> result = new LinkedHashMap<>();
        for (LeadTagAssignment a : assignments) {
            String name = nameById.get(a.getTagId());
            if (name != null) {
                result.computeIfAbsent(a.getLeadId(), k -> new ArrayList<>()).add(name);
            }
        }
        return result;
    }

    /** Loads a lead, enforcing salesperson scoping (Req 3.1) with a 404 when out of scope. */
    private LeadEntity loadScoped(Long id, AuthPrincipal actor) {
        Optional<Long> constraint = scopeResolver.creatorConstraint(actor);
        if (constraint.isPresent()) {
            return leadRepository.findByIdAndOwnerUserId(id, constraint.get())
                    .orElseThrow(() -> new ResourceNotFoundException("Lead " + id + " does not exist."));
        }
        return leadRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Lead " + id + " does not exist."));
    }

    private static void validateLength(String value, int max, String field) {
        if (value != null && value.length() > max) {
            throw new ValidationException(field + " must be at most " + max + " characters.");
        }
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String idOf(LeadEntity lead) {
        return lead.getId() == null ? null : String.valueOf(lead.getId());
    }
}
