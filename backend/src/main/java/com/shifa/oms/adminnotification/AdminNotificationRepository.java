package com.shifa.oms.adminnotification;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

/**
 * Spring Data repository for {@link AdminNotification} rows.
 *
 * <p>The filtered finder backs {@code GET /api/admin/notifications}: both filters
 * are optional ({@code unreadOnly} and an exact {@code type}), so one query
 * serves the full list and the unread badge/drawer. {@link #countByReadFalse()}
 * powers the unread-count endpoint, and {@link #markAllRead} flips every unread
 * row in a single bulk update.
 */
public interface AdminNotificationRepository extends JpaRepository<AdminNotification, Long> {

    /** Whether a notification already exists for the given originating outbox event (de-dup guard). */
    boolean existsBySourceEventId(Long sourceEventId);

    /**
     * Composite de-dup guard for the role/user fan-out (design §5.2): whether a
     * notification already exists for the same originating event <em>and</em> the
     * same recipient addressing. One lifecycle event now yields several recipient
     * rows, so de-duplication moves to this composite at the write site rather
     * than the V16 single-column guard.
     */
    boolean existsBySourceEventIdAndRecipientRoleAndRecipientUserId(
            Long sourceEventId, com.shifa.oms.auth.Role recipientRole, Long recipientUserId);

    /** Count of unread notifications (unread badge). */
    long countByReadFalse();

    /**
     * Filtered, paged notifications <em>visible to a specific staff user</em>
     * (Req 13.4): those addressed to the user id, to the user's role, or (when the
     * user is an admin) the legacy admin broadcasts (both recipient fields null).
     * Mirrors {@link StaffNotificationVisibility#reaches}.
     *
     * @param userId        the querying user's id
     * @param role          the querying user's role
     * @param legacyVisible whether legacy admin broadcasts are visible (role == ADMIN)
     * @param unreadOnly    when true, only unread rows
     * @param type          exact type filter (nullable)
     */
    @Query("""
            SELECT n FROM AdminNotification n
            WHERE (:unreadOnly = false OR n.read = false)
              AND (:type IS NULL OR n.type = :type)
              AND (
                    n.recipientUserId = :userId
                 OR n.recipientRole = :role
                 OR (n.recipientRole IS NULL AND n.recipientUserId IS NULL AND :legacyVisible = true)
              )
            """)
    Page<AdminNotification> searchForUser(@Param("userId") Long userId,
                                          @Param("role") com.shifa.oms.auth.Role role,
                                          @Param("legacyVisible") boolean legacyVisible,
                                          @Param("unreadOnly") boolean unreadOnly,
                                          @Param("type") String type,
                                          Pageable pageable);

    /** Count of unread notifications visible to a specific staff user (per-user badge). */
    @Query("""
            SELECT COUNT(n) FROM AdminNotification n
            WHERE n.read = false
              AND (
                    n.recipientUserId = :userId
                 OR n.recipientRole = :role
                 OR (n.recipientRole IS NULL AND n.recipientUserId IS NULL AND :legacyVisible = true)
              )
            """)
    long countUnreadForUser(@Param("userId") Long userId,
                            @Param("role") com.shifa.oms.auth.Role role,
                            @Param("legacyVisible") boolean legacyVisible);

    /**
     * Filtered, paged notifications. When {@code unreadOnly} is true only unread
     * rows are returned; when {@code type} is non-null only that type is returned.
     * Ordered by the caller-supplied {@link Pageable} (default newest-first).
     */
    @Query("""
            SELECT n FROM AdminNotification n
            WHERE (:unreadOnly = false OR n.read = false)
              AND (:type IS NULL OR n.type = :type)
            """)
    Page<AdminNotification> search(@Param("unreadOnly") boolean unreadOnly,
                                   @Param("type") String type,
                                   Pageable pageable);

    /**
     * Marks every currently-unread notification read in a single bulk update,
     * stamping {@code read_at}. Returns the number of rows flipped.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE AdminNotification n SET n.read = true, n.readAt = :now WHERE n.read = false")
    int markAllRead(@Param("now") LocalDateTime now);
}
