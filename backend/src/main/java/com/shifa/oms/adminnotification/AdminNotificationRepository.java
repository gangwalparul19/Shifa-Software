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

    /** Count of unread notifications (unread badge). */
    long countByReadFalse();

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
