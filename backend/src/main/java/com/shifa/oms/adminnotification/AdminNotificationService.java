package com.shifa.oms.adminnotification;

import com.shifa.oms.adminnotification.dto.AdminNotificationResponse;
import com.shifa.oms.auth.AuthPrincipal;
import com.shifa.oms.auth.Role;
import com.shifa.oms.common.PageResponse;
import com.shifa.oms.common.ResourceNotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Application service for the admin notifications center ("operations depth"
 * Feature 2): persisting admin alerts and exposing a read/unread history.
 *
 * <p>{@link #record} is the create entry point (used by the outbox sink and by
 * any code that wants to raise a durable admin alert). It de-duplicates on the
 * originating outbox event id so the same event never produces two rows. The
 * read operations back {@code /api/admin/notifications}.
 */
@Service
public class AdminNotificationService {

    private final AdminNotificationRepository repository;

    public AdminNotificationService(AdminNotificationRepository repository) {
        this.repository = repository;
    }

    /**
     * Persists an admin notification. When {@code sourceEventId} is non-null and a
     * notification already exists for that event, this is a no-op returning the
     * existing count semantics (idempotent de-dup), so a retrying producer never
     * doubles a row.
     *
     * @param type        the notification/event type (e.g. {@code ORDER_PACKED})
     * @param title       a short headline
     * @param detail      an optional longer description (nullable)
     * @param severity    one of info|success|warning|danger (nullable → info)
     * @param orderId     the related order id, when order-scoped (nullable)
     * @param orderCode   the related order code, when order-scoped (nullable)
     * @param sourceEventId the originating outbox event id for de-dup (nullable)
     * @return the persisted (or pre-existing) notification, or {@code null} when de-duplicated
     */
    @Transactional
    public AdminNotification record(String type, String title, String detail, String severity,
                                    Long orderId, String orderCode, Long sourceEventId) {
        if (sourceEventId != null && repository.existsBySourceEventId(sourceEventId)) {
            return null;
        }
        return repository.save(new AdminNotification(
                type, title, detail, severity, orderId, orderCode, sourceEventId));
    }

    /**
     * Filtered, paged, newest-first notifications.
     *
     * @param unreadOnly when true, return only unread notifications
     * @param type       exact type filter (nullable)
     * @param pageable   page / size / sort
     */
    @Transactional(readOnly = true)
    public PageResponse<AdminNotificationResponse> list(boolean unreadOnly, String type,
                                                        Pageable pageable) {
        Page<AdminNotification> page = repository.search(unreadOnly, blankToNull(type), pageable);
        return PageResponse.of(page, AdminNotificationResponse::from);
    }

    /** The number of unread notifications (console badge). */
    @Transactional(readOnly = true)
    public long unreadCount() {
        return repository.countByReadFalse();
    }

    /**
     * Filtered, paged, newest-first notifications <em>visible to a specific staff
     * user</em> (Req 13.4): addressed to their user id, to their role, or (for
     * admins) the legacy admin broadcasts. Backs the staff-facing
     * {@code GET /api/notifications}.
     *
     * @param principal the authenticated staff user
     * @param unreadOnly when true, return only unread notifications
     * @param type       exact type filter (nullable)
     * @param pageable   page / size / sort
     */
    @Transactional(readOnly = true)
    public PageResponse<AdminNotificationResponse> listForUser(AuthPrincipal principal,
                                                               boolean unreadOnly, String type,
                                                               Pageable pageable) {
        boolean legacyVisible = principal.role() == Role.ADMIN;
        Page<AdminNotification> page = repository.searchForUser(
                principal.userId(), principal.role(), legacyVisible,
                unreadOnly, blankToNull(type), pageable);
        return PageResponse.of(page, AdminNotificationResponse::from);
    }

    /** The number of unread notifications visible to a specific staff user (per-user badge). */
    @Transactional(readOnly = true)
    public long unreadCountForUser(AuthPrincipal principal) {
        boolean legacyVisible = principal.role() == Role.ADMIN;
        return repository.countUnreadForUser(principal.userId(), principal.role(), legacyVisible);
    }

    /**
     * Marks a single notification read (idempotent). A 404 is raised when the id
     * does not exist.
     */
    @Transactional
    public AdminNotificationResponse markRead(Long id) {
        AdminNotification notification = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Notification " + id + " does not exist."));
        notification.markRead();
        return AdminNotificationResponse.from(repository.save(notification));
    }

    /**
     * Marks every unread notification read in one bulk update.
     *
     * @return the number of notifications flipped to read
     */
    @Transactional
    public int markAllRead() {
        return repository.markAllRead(LocalDateTime.now());
    }

    /**
     * Marks every unread notification <em>visible to a specific staff user</em>
     * read in one bulk update (Req 13.4). Backs the per-user bell's "Mark all
     * read" so a user clears exactly their own role/user-addressed alerts.
     *
     * @param principal the authenticated staff user
     * @return the number of notifications flipped to read
     */
    @Transactional
    public int markAllReadForUser(AuthPrincipal principal) {
        boolean legacyVisible = principal.role() == Role.ADMIN;
        return repository.markAllReadForUser(
                principal.userId(), principal.role(), legacyVisible, LocalDateTime.now());
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
