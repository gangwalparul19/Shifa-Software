package com.shifa.oms.lead;

import com.shifa.oms.audit.AuditActions;
import com.shifa.oms.audit.AuditService;
import com.shifa.oms.auth.AuthPrincipal;
import com.shifa.oms.auth.SalespersonScopeResolver;
import com.shifa.oms.common.IllegalLeadTransitionException;
import com.shifa.oms.common.ResourceNotFoundException;
import com.shifa.oms.common.ValidationException;
import com.shifa.oms.lead.dto.CreateLeadRequest;
import com.shifa.oms.lead.dto.LeadConvertRequest;
import com.shifa.oms.lead.dto.LeadReports.BySourceReport;
import com.shifa.oms.lead.dto.LeadReports.ConversionReport;
import com.shifa.oms.lead.dto.LeadReports.LostReasonReport;
import com.shifa.oms.lead.dto.LeadReports.PipelineReport;
import com.shifa.oms.lead.dto.LeadResponse;
import com.shifa.oms.lead.dto.LeadSummaryResponse;
import com.shifa.oms.order.LeadSource;
import com.shifa.oms.order.OrderService;
import com.shifa.oms.order.dto.CreateOrderRequest;
import com.shifa.oms.order.dto.OrderResponse;
import com.shifa.oms.reporting.domain.DateRange;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.EnumMap;
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

    private final LeadRepository leadRepository;
    private final SalespersonScopeResolver scopeResolver;
    private final AuditService auditService;
    private final OrderService orderService;
    private final Clock clock;

    /** Production constructor (Spring): uses the system UTC clock for "today". */
    @Autowired
    public LeadService(LeadRepository leadRepository,
                       SalespersonScopeResolver scopeResolver,
                       AuditService auditService,
                       OrderService orderService) {
        this(leadRepository, scopeResolver, auditService, orderService, Clock.systemUTC());
    }

    /** Test constructor with an injected clock (deterministic due-follow-up "today"). */
    public LeadService(LeadRepository leadRepository,
                       SalespersonScopeResolver scopeResolver,
                       AuditService auditService,
                       OrderService orderService,
                       Clock clock) {
        this.leadRepository = Objects.requireNonNull(leadRepository, "leadRepository");
        this.scopeResolver = Objects.requireNonNull(scopeResolver, "scopeResolver");
        this.auditService = Objects.requireNonNull(auditService, "auditService");
        this.orderService = Objects.requireNonNull(orderService, "orderService");
        this.clock = Objects.requireNonNull(clock, "clock");
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
        // Creation history row: null from-status → initial NEW status (Req 2.1, 7.5).
        lead.addStatusHistory(new LeadStatusHistory(null, LeadStatus.INITIAL, actor.username()));

        LeadEntity saved = leadRepository.save(lead);
        auditService.record(AuditActions.LEAD_CAPTURED, AuditActions.ENTITY_LEAD, idOf(saved),
                "Lead captured for " + name.trim() + " via " + source);
        return LeadResponse.from(saved);
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

        LeadEntity saved = leadRepository.save(lead);
        auditService.record(AuditActions.LEAD_STATUS_CHANGED, AuditActions.ENTITY_LEAD, idOf(saved),
                "Lead " + id + " " + from + " \u2192 " + toStatus + " by " + actor.username());
        return LeadResponse.from(saved);
    }

    // --- Follow-up (Req 5.1) ------------------------------------------------

    /**
     * Sets or clears a lead's follow-up date (Req 5.1, 5.5). A {@code null} date
     * clears it; setting/updating the date re-arms the reminder by clearing the
     * "last reminded" marker so the lead can be reminded again on its new due day.
     */
    @Transactional
    public LeadResponse setFollowUp(Long id, LocalDate followUpDate, AuthPrincipal actor) {
        Objects.requireNonNull(actor, "actor");
        LeadEntity lead = loadScoped(id, actor);
        lead.setFollowUpDate(followUpDate);
        lead.setRemindedOn(null);
        LeadEntity saved = leadRepository.save(lead);
        auditService.record(AuditActions.LEAD_FOLLOW_UP_SET, AuditActions.ENTITY_LEAD, idOf(saved),
                followUpDate == null
                        ? "Lead " + id + " follow-up cleared"
                        : "Lead " + id + " follow-up set to " + followUpDate);
        return LeadResponse.from(saved);
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
        if (request.followUpDate() != null && !request.followUpDate().equals(lead.getFollowUpDate())) {
            // Re-arm the reminder when the follow-up date changes (mirrors setFollowUp).
            lead.setRemindedOn(null);
        }
        lead.setFollowUpDate(request.followUpDate());

        LeadEntity saved = leadRepository.save(lead);
        auditService.record(AuditActions.LEAD_STATUS_CHANGED, AuditActions.ENTITY_LEAD, idOf(saved),
                "Lead " + id + " capture fields edited by " + actor.username());
        return LeadResponse.from(saved);
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
                null);

        // Same transaction: a failure here rolls the whole convert back (Req 4.4).
        OrderResponse order = orderService.createSalespersonOrder(orderRequest, actor);

        lead.setStatus(LeadStatus.WON);
        lead.setConvertedOrderId(order.id());
        lead.addStatusHistory(new LeadStatusHistory(from, LeadStatus.WON, actor.username()));

        LeadEntity saved = leadRepository.save(lead);
        auditService.record(AuditActions.LEAD_CONVERTED, AuditActions.ENTITY_LEAD, idOf(saved),
                "Lead " + id + " converted to order " + order.id() + " (" + order.orderCode()
                        + ") by " + actor.username());
        return LeadResponse.from(saved);
    }

    // --- Scoped reads (Req 3) ----------------------------------------------

    /**
     * Role-scoped lead list with optional name/mobile search and status/source
     * filters (Req 3.1-3.4). A salesperson sees only their own leads; an admin
     * sees all.
     */
    @Transactional(readOnly = true)
    public List<LeadSummaryResponse> list(String q, LeadStatus status, LeadSource source,
                                          AuthPrincipal actor) {
        Long ownerUserId = scopeResolver.creatorConstraint(actor).orElse(null);
        String term = (q == null || q.isBlank()) ? null : q.trim();
        String statusName = status == null ? null : status.name();
        String sourceName = source == null ? null : source.name();
        return leadRepository.search(ownerUserId, term, statusName, sourceName).stream()
                .map(LeadSummaryResponse::from)
                .toList();
    }

    /** Scoped lead detail incl. status history (Req 3.5); 404 when out of scope (Req 3.1). */
    @Transactional(readOnly = true)
    public LeadResponse detail(Long id, AuthPrincipal actor) {
        return LeadResponse.from(loadScoped(id, actor));
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
