package com.shifa.oms.adminnotification;

import com.shifa.oms.adminnotification.dto.AdminNotificationResponse;
import com.shifa.oms.adminnotification.dto.UnreadCountResponse;
import com.shifa.oms.auth.AuthPrincipal;
import com.shifa.oms.auth.CurrentUserService;
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
 * Staff-facing notifications endpoints ({@code /api/notifications}, design §5.2,
 * §6, Req 13.4).
 *
 * <p>Unlike the admin-only {@link AdminNotificationController}
 * ({@code /api/admin/notifications}), this endpoint is available to <em>any</em>
 * authenticated staff user and returns exactly the notifications addressed to
 * that user's role or user id (plus the legacy admin broadcasts for admins), so
 * the per-user notification bell reflects role/user-addressed alerts rather than
 * only admin broadcasts.
 *
 * <ul>
 *   <li>{@code GET /api/notifications?unreadOnly=&type=&page=&size=} — paged list;</li>
 *   <li>{@code GET /api/notifications/unread-count} — per-user unread badge count;</li>
 *   <li>{@code POST /api/notifications/{id}/read} — mark one read.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/notifications")
@PreAuthorize("isAuthenticated()")
public class StaffNotificationController {

    /** Whitelist of API sort fields → JPA properties for the notifications table. */
    private static final Map<String, String> SORT_WHITELIST = Map.of(
            "createdAt", "createdAt",
            "type", "type");

    private static final Sort DEFAULT_SORT = Sort.by(Sort.Direction.DESC, "createdAt");

    private final AdminNotificationService notificationService;
    private final CurrentUserService currentUserService;

    public StaffNotificationController(AdminNotificationService notificationService,
                                       CurrentUserService currentUserService) {
        this.notificationService = notificationService;
        this.currentUserService = currentUserService;
    }

    /** Filtered, paged, newest-first notifications visible to the current staff user. */
    @GetMapping
    public PageResponse<AdminNotificationResponse> list(
            @RequestParam(required = false, defaultValue = "false") boolean unreadOnly,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort) {
        AuthPrincipal principal = currentUserService.requireCurrentUser();
        Pageable pageable = PageRequests.of(page, size, sort, SORT_WHITELIST, DEFAULT_SORT);
        return notificationService.listForUser(principal, unreadOnly, type, pageable);
    }

    /** The unread notification count visible to the current staff user (bell badge). */
    @GetMapping("/unread-count")
    public UnreadCountResponse unreadCount() {
        AuthPrincipal principal = currentUserService.requireCurrentUser();
        return new UnreadCountResponse(notificationService.unreadCountForUser(principal));
    }

    /** Marks a single notification read (idempotent); 404 when the id is unknown. */
    @PostMapping("/{id}/read")
    public AdminNotificationResponse markRead(@PathVariable Long id) {
        return notificationService.markRead(id);
    }
}
