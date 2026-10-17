package com.shifa.oms.meeting;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * Spring Data repository over the {@link MeetingEntity} aggregate (CRM expansion
 * Phase 2, Task 11). Owner-scoped reads in SQL; the reminder source is unscoped.
 */
public interface MeetingRepository extends JpaRepository<MeetingEntity, Long> {

    /**
     * Owner-scoped meeting list, soonest first. A {@code null} {@code ownerIds}
     * disables scoping (admin); an optional {@code status} filter matches the
     * enum name (Req 9.4).
     */
    @Query("""
            SELECT m FROM MeetingEntity m
            WHERE (:ownerIds IS NULL OR m.ownerUserId IN :ownerIds)
              AND (:status IS NULL OR m.status = :status)
            ORDER BY m.startAt ASC, m.id DESC
            """)
    List<MeetingEntity> findScoped(@Param("ownerIds") Collection<Long> ownerIds,
                                   @Param("status") MeetingStatus status);

    /**
     * All SCHEDULED meetings starting within the reminder window
     * ({@code start_at} between {@code now} and {@code windowEnd}) not yet
     * reminded today — the unscoped source the {@code MeetingReminderJob} drains.
     */
    @Query("""
            SELECT m FROM MeetingEntity m
            WHERE m.status = com.shifa.oms.meeting.MeetingStatus.SCHEDULED
              AND m.startAt >= :now
              AND m.startAt <= :windowEnd
              AND (m.remindedOn IS NULL OR m.remindedOn <> :today)
            ORDER BY m.startAt ASC, m.id ASC
            """)
    List<MeetingEntity> findUpcomingForReminder(@Param("now") LocalDateTime now,
                                                @Param("windowEnd") LocalDateTime windowEnd,
                                                @Param("today") LocalDate today);
}
