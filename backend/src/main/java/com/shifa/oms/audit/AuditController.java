package com.shifa.oms.audit;

import com.shifa.oms.audit.dto.AuditEventResponse;
import com.shifa.oms.common.PageRequests;
import com.shifa.oms.common.PageResponse;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Map;

/**
 * Read-only global audit-trail endpoint ({@code /api/admin/audit},
 * "operations depth" Feature 3).
 *
 * <p>Restricted to the {@code ADMIN} role via method security; unauthenticated
 * callers get 401 and non-admins 403 (standard error envelope). Returns the
 * newest-first {@link PageResponse} envelope so the admin console can page the
 * trail; every filter is optional.
 */
@RestController
@RequestMapping("/api/admin/audit")
@PreAuthorize("hasRole('ADMIN')")
public class AuditController {

    /** Whitelist of API sort fields → JPA properties for the audit table. */
    private static final Map<String, String> SORT_WHITELIST = Map.of(
            "createdAt", "createdAt",
            "action", "action",
            "entityType", "entityType");

    private static final Sort DEFAULT_SORT = Sort.by(Sort.Direction.DESC, "createdAt");

    private final AuditService auditService;

    public AuditController(AuditService auditService) {
        this.auditService = auditService;
    }

    /**
     * Filtered, paged, newest-first audit trail.
     *
     * @param action     exact action verb filter, e.g. {@code ORDER_APPROVED} (optional)
     * @param entityType exact entity-type filter, e.g. {@code ORDER} (optional)
     * @param q          case-insensitive substring over summary / actor / entity id (optional)
     * @param from       inclusive lower-bound date, ISO {@code yyyy-MM-dd} (optional)
     * @param to         inclusive upper-bound date, ISO {@code yyyy-MM-dd} (optional)
     * @param page       zero-based page index (default 0)
     * @param size       page size (default 20, capped at 100)
     * @param sort       {@code field,dir} — one of createdAt/action/entityType
     */
    @GetMapping
    public PageResponse<AuditEventResponse> list(
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort) {
        Pageable pageable = PageRequests.of(page, size, sort, SORT_WHITELIST, DEFAULT_SORT);
        LocalDateTime fromTs = from != null ? from.atStartOfDay() : null;
        LocalDateTime toTs = to != null ? to.atTime(LocalTime.MAX) : null;
        return auditService.list(action, entityType, q, fromTs, toTs, pageable);
    }
}
