package com.shifa.oms.audit;

import com.shifa.oms.auth.AuthPrincipal;
import com.shifa.oms.auth.CurrentUserService;
import com.shifa.oms.common.PageResponse;
import com.shifa.oms.audit.dto.AuditEventResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Application service for the global audit trail ("operations depth" Feature 3).
 *
 * <p>{@link #record} is the single write entry point used by the instrumented
 * mutating operations. It resolves the acting principal via
 * {@link CurrentUserService} (best-effort: the actor fields are left null when
 * there is no authenticated context, e.g. a scheduled task) and is deliberately
 * <strong>never-throwing</strong> — an audit write failure must never break the
 * business operation it describes, so any exception is swallowed and logged.
 *
 * <p>{@link #list} backs the read-only {@code GET /api/admin/audit} endpoint,
 * returning newest-first pages through the shared {@link PageResponse} envelope.
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditEventRepository repository;
    private final CurrentUserService currentUserService;

    public AuditService(AuditEventRepository repository, CurrentUserService currentUserService) {
        this.repository = repository;
        this.currentUserService = currentUserService;
    }

    /**
     * Records an audit event for the current actor. Best-effort: resolves the
     * principal from the security context (null actor when unauthenticated) and
     * never throws — a failure to write the trail is logged and swallowed so the
     * calling operation is unaffected.
     *
     * @param action     the action verb (see {@link AuditActions})
     * @param entityType the target entity type discriminator
     * @param entityId   the target entity id (nullable)
     * @param summary    a short human-readable description (nullable)
     * @return the persisted event, or {@code null} when the write was skipped/failed
     */
    @Transactional
    public AuditEvent record(String action, String entityType, String entityId, String summary) {
        try {
            Long actorId = null;
            String actorUsername = null;
            AuthPrincipal principal = currentUserService.currentUser().orElse(null);
            if (principal != null) {
                actorId = principal.userId();
                actorUsername = principal.username();
            }
            return repository.save(new AuditEvent(
                    actorId, actorUsername, action, entityType, entityId, truncate(summary)));
        } catch (RuntimeException e) {
            log.warn("Failed to record audit event {} for {} {}: {}",
                    action, entityType, entityId, e.getMessage());
            return null;
        }
    }

    /**
     * Filtered, paged, newest-first audit trail for the admin console.
     *
     * @param action     exact action-verb filter (nullable)
     * @param entityType exact entity-type filter (nullable)
     * @param q          case-insensitive substring over summary/actor/entity id (nullable)
     * @param from       inclusive lower bound on {@code created_at} (nullable)
     * @param to         inclusive upper bound on {@code created_at} (nullable)
     * @param pageable   page / size / sort (default newest-first)
     */
    @Transactional(readOnly = true)
    public PageResponse<AuditEventResponse> list(String action, String entityType, String q,
                                                 LocalDateTime from, LocalDateTime to,
                                                 Pageable pageable) {
        Page<AuditEvent> page = repository.search(
                blankToNull(action), blankToNull(entityType), blankToNull(q), from, to, pageable);
        return PageResponse.of(page, AuditEventResponse::from);
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() > 1000 ? value.substring(0, 1000) : value;
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
