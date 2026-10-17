package com.shifa.oms.activity;

import com.shifa.oms.activity.dto.ActivityResponse;
import com.shifa.oms.auth.AuthPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * The single writer for the unified CRM activity log (CRM expansion Phase 2,
 * Task 9, design &sect;2.1) and the reader behind the per-lead / per-customer
 * timelines (Req 11.2).
 *
 * <p>Every CRM touch point (lead stage/tag/assign/follow-up, task create/done,
 * meeting schedule/outcome, and the frontend call/WhatsApp log) routes through
 * {@link #record} so the timeline is a faithful superset of what happened. The
 * writer is <strong>best-effort / never-throwing</strong> — exactly like
 * {@link com.shifa.oms.audit.AuditService} — so a timeline write can never break
 * the business operation that produced it. The actor may be {@code null} for a
 * system-driven activity (e.g. the missed-follow-up job).
 */
@Service
public class ActivityService {

    private static final Logger log = LoggerFactory.getLogger(ActivityService.class);

    private static final int MAX_SUMMARY = 500;
    private static final int MAX_DETAIL = 1000;

    private final ActivityRepository repository;

    public ActivityService(ActivityRepository repository) {
        this.repository = repository;
    }

    /**
     * Records one activity row. Best-effort: never throws, so a failed timeline
     * write is logged and swallowed. Returns the persisted row, or {@code null}
     * when the write was skipped/failed.
     *
     * @param type           an {@link ActivityTypes} discriminator
     * @param leadId         the lead the activity concerns (nullable)
     * @param customerMobile the customer mobile the activity concerns (nullable)
     * @param actor          the acting principal (nullable for a system write)
     * @param summary        a short human line (required)
     * @param detail         optional longer detail (nullable)
     */
    @Transactional
    public ActivityEntry record(String type, Long leadId, String customerMobile,
                                AuthPrincipal actor, String summary, String detail) {
        try {
            Long actorId = actor == null ? null : actor.userId();
            String actorName = actor == null ? null : actor.username();
            return repository.save(new ActivityEntry(
                    type, leadId, normalizeMobile(customerMobile), actorId, actorName,
                    truncate(summary, MAX_SUMMARY), truncate(detail, MAX_DETAIL)));
        } catch (RuntimeException e) {
            log.warn("Failed to record activity {} (lead={}, mobile={}): {}",
                    type, leadId, customerMobile, e.getMessage());
            return null;
        }
    }

    /** Records an activity for an explicitly supplied actor id/name (e.g. SYSTEM). */
    @Transactional
    public ActivityEntry record(String type, Long leadId, String customerMobile,
                                Long actorUserId, String actorName, String summary, String detail) {
        try {
            return repository.save(new ActivityEntry(
                    type, leadId, normalizeMobile(customerMobile), actorUserId, actorName,
                    truncate(summary, MAX_SUMMARY), truncate(detail, MAX_DETAIL)));
        } catch (RuntimeException e) {
            log.warn("Failed to record activity {} (lead={}, mobile={}): {}",
                    type, leadId, customerMobile, e.getMessage());
            return null;
        }
    }

    /** A lead's activity timeline, newest first (Req 11.2). */
    @Transactional(readOnly = true)
    public List<ActivityResponse> timelineForLead(Long leadId) {
        return repository.findByLeadIdOrderByCreatedAtDesc(leadId).stream()
                .map(ActivityResponse::from)
                .toList();
    }

    /** A customer's activity timeline (crm_activities only), newest first (Req 11.2). */
    @Transactional(readOnly = true)
    public List<ActivityResponse> timelineForCustomer(String customerMobile) {
        return repository.findByCustomerMobileOrderByCreatedAtDesc(normalizeMobile(customerMobile))
                .stream()
                .map(ActivityResponse::from)
                .toList();
    }

    private static String normalizeMobile(String mobile) {
        if (mobile == null) {
            return null;
        }
        String trimmed = mobile.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() > max ? value.substring(0, max) : value;
    }
}
