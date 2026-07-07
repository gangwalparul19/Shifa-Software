package com.shifa.oms.adminnotification;

import com.shifa.oms.auth.Role;

import java.util.Objects;

/**
 * Pure visibility rule for staff in-app notifications (design §3.3, §5.2,
 * Req 13.4). A single place that decides whether a notification addressed to a
 * {@code recipientRole} / {@code recipientUserId} is visible to a given user,
 * kept side-effect-free so it can be property-tested in-memory (Property 19) and
 * mirrored exactly by the repository query.
 *
 * <p>A notification reaches a user when any of the following holds:
 * <ul>
 *   <li>it is addressed to that specific user
 *       ({@code recipientUserId == userId});</li>
 *   <li>it is addressed to the user's role
 *       ({@code recipientRole == userRole});</li>
 *   <li>it is a legacy admin broadcast (both recipient fields null) and the user
 *       is an {@link Role#ADMIN}.</li>
 * </ul>
 * Because role-addressed rows are shared across a role, "reaches exactly that
 * role's active users" follows directly: only active users query, and among them
 * exactly those whose role matches see the row.
 */
public final class StaffNotificationVisibility {

    private StaffNotificationVisibility() {
    }

    /**
     * Whether a notification with the given addressing is visible to a user.
     *
     * @param recipientRole   the notification's target role (nullable)
     * @param recipientUserId the notification's target user id (nullable)
     * @param userRole        the querying user's role (never null)
     * @param userId          the querying user's id (never null)
     * @return {@code true} when the user should see the notification
     */
    public static boolean reaches(Role recipientRole, Long recipientUserId,
                                  Role userRole, Long userId) {
        Objects.requireNonNull(userRole, "userRole");
        Objects.requireNonNull(userId, "userId");
        if (recipientUserId != null) {
            return recipientUserId.equals(userId);
        }
        if (recipientRole != null) {
            return recipientRole == userRole;
        }
        // Legacy admin broadcast (both null) — visible to admins only.
        return userRole == Role.ADMIN;
    }
}
