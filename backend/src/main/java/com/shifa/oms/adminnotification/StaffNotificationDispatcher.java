package com.shifa.oms.adminnotification;

import com.shifa.oms.auth.Role;
import org.springframework.stereotype.Service;

/**
 * Fans a lifecycle notification out to role-addressed / user-addressed
 * {@link AdminNotification} rows (design §5.2, Req 13.3, 13.4), the staff in-app
 * side of the {@code NotificationMatrix}.
 *
 * <p>Unlike the legacy {@link AdminNotificationOutboxSink} (which writes a single
 * global admin-broadcast row per event), this dispatcher writes one row per
 * matrix recipient:
 * <ul>
 *   <li>{@link #dispatchToRole} — addressed to a {@link Role}; a single shared
 *       row that every active user of that role sees via the per-user query
 *       (Req 13.4);</li>
 *   <li>{@link #dispatchToUser} — addressed to a specific user id, e.g. the
 *       creating salesperson (Req 7.3).</li>
 * </ul>
 *
 * <p>Rows are written within the caller's transaction (the workflow transaction),
 * so the in-app notifications commit atomically with the status change (Req 14.1).
 * De-duplication is a composite of {@code (source_event_id, recipient_role,
 * recipient_user_id)} at the write site, since one event now yields several rows;
 * when {@code sourceEventId} is null (the inline workflow path fires exactly once
 * per transition) no de-dup check is needed.
 */
@Service
public class StaffNotificationDispatcher {

    private final AdminNotificationRepository repository;

    public StaffNotificationDispatcher(AdminNotificationRepository repository) {
        this.repository = repository;
    }

    /**
     * Writes a role-addressed in-app notification (visible to every active user of
     * {@code role}). Idempotent on the composite key when {@code sourceEventId} is
     * non-null.
     *
     * @return the persisted row, or {@code null} when de-duplicated / role is null
     */
    public AdminNotification dispatchToRole(String type, String title, String detail,
                                            String severity, Long orderId, String orderCode,
                                            Long sourceEventId, Role role) {
        if (role == null) {
            return null;
        }
        if (isDuplicate(sourceEventId, role, null)) {
            return null;
        }
        AdminNotification notification = new AdminNotification(
                type, title, detail, severity, orderId, orderCode, sourceEventId);
        notification.setRecipientRole(role);
        return repository.save(notification);
    }

    /**
     * Writes a user-addressed in-app notification (visible to exactly that user,
     * e.g. the creating salesperson). Idempotent on the composite key when
     * {@code sourceEventId} is non-null.
     *
     * @return the persisted row, or {@code null} when de-duplicated / user id is null
     */
    public AdminNotification dispatchToUser(String type, String title, String detail,
                                            String severity, Long orderId, String orderCode,
                                            Long sourceEventId, Long userId) {
        if (userId == null) {
            return null;
        }
        if (isDuplicate(sourceEventId, null, userId)) {
            return null;
        }
        AdminNotification notification = new AdminNotification(
                type, title, detail, severity, orderId, orderCode, sourceEventId);
        notification.setRecipientUserId(userId);
        return repository.save(notification);
    }

    private boolean isDuplicate(Long sourceEventId, Role role, Long userId) {
        return sourceEventId != null
                && repository.existsBySourceEventIdAndRecipientRoleAndRecipientUserId(
                        sourceEventId, role, userId);
    }
}
