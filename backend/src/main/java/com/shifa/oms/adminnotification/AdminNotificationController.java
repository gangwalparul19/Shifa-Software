package com.shifa.oms.adminnotification;

import com.shifa.oms.adminnotification.dto.AdminNotificationResponse;
import com.shifa.oms.adminnotification.dto.UnreadCountResponse;
import com.shifa.oms.common.PageRequests;
import com.shifa.oms.common.PageResponse;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Admin notifications center endpoints ({@code /api/admin/notifications},
 * "operations depth" Feature 2).
 *
 * <p>Restricted to the {@code ADMIN} role via method security; unauthenticated
 * callers get 401 and non-admins 403 (standard error envelope). Provides the
 * persisted history with read/unread state that complements the ephemeral SSE
 * stream (the SSE contract is unchanged).
 *
 * <ul>
 *   <li>{@code GET /api/admin/notifications?unreadOnly=&type=&page=&size=} — paged list;</li>
 *   <li>{@code GET /api/admin/notifications/unread-count} — unread badge count;</li>
 *   <li>{@code POST /api/admin/notifications/{id}/read} — mark one read;</li>
 *   <li>{@code POST /api/admin/notifications/read-all} — mark all read.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/admin/notifications")
@PreAuthorize("hasRole('ADMIN')")
public class AdminNotificationController {

    /** Whitelist of API sort fields → JPA properties for the notifications table. */
    private static final Map<String, String> SORT_WHITELIST = Map.of(
            "createdAt", "createdAt",
            "type", "type");

    private static final Sort DEFAULT_SORT = Sort.by(Sort.Direction.DESC, "createdAt");

    private final AdminNotificationService notificationService;

    public AdminNotificationController(AdminNotificationService notificationService) {
        this.notificationService = notificationService;
    }

    /**
     * Filtered, paged, newest-first notifications.
     *
     * @param unreadOnly when true, only unread notifications (default false)
     * @param type       exact type filter, e.g. {@code ORDER_PACKED} (optional)
     * @param page       zero-based page index (default 0)
     * @param size       page size (default 20, capped at 100)
     * @param sort       {@code field,dir} — one of createdAt/type
     */
    @GetMapping
    public PageResponse<AdminNotificationResponse> list(
            @RequestParam(required = false, defaultValue = "false") boolean unreadOnly,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort) {
        Pageable pageable = PageRequests.of(page, size, sort, SORT_WHITELIST, DEFAULT_SORT);
        return notificationService.list(unreadOnly, type, pageable);
    }

    /** The unread notification count for the console badge. */
    @GetMapping("/unread-count")
    public UnreadCountResponse unreadCount() {
        return new UnreadCountResponse(notificationService.unreadCount());
    }

    /** Marks a single notification read (idempotent); 404 when the id is unknown. */
    @PostMapping("/{id}/read")
    public AdminNotificationResponse markRead(@PathVariable Long id) {
        return notificationService.markRead(id);
    }

    /** Marks every unread notification read; returns the updated unread count (0). */
    @PostMapping("/read-all")
    public UnreadCountResponse markAllRead() {
        notificationService.markAllRead();
        return new UnreadCountResponse(notificationService.unreadCount());
    }
}
