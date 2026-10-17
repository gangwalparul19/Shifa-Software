package com.shifa.oms.task;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * Spring Data repository over the {@link TaskEntity} aggregate (CRM expansion
 * Phase 2, Task 10). Reads are owner-scoped in SQL ({@code owner_user_id IN
 * (:ownerIds)}) so a salesperson/team-lead query cannot be widened by request
 * manipulation; an unscoped (admin) caller passes a {@code null} owner set.
 */
public interface TaskRepository extends JpaRepository<TaskEntity, Long> {

    /**
     * Owner-scoped task list, newest first. A {@code null} {@code ownerIds}
     * disables scoping (admin/unscoped); a non-null collection restricts to those
     * owners (Req 8.3). An optional {@code status} filter matches the enum name.
     */
    @Query("""
            SELECT t FROM TaskEntity t
            WHERE (:ownerIds IS NULL OR t.ownerUserId IN :ownerIds)
              AND (:status IS NULL OR t.status = :status)
            ORDER BY (CASE WHEN t.dueAt IS NULL THEN 1 ELSE 0 END), t.dueAt ASC, t.id DESC
            """)
    List<TaskEntity> findScoped(@Param("ownerIds") Collection<Long> ownerIds,
                                @Param("status") TaskStatus status);

    /**
     * All OPEN tasks due at or before {@code now} that have not yet been reminded
     * on {@code today} — the unscoped source the {@code TaskReminderJob} drains
     * (design &sect;2.2, mirroring {@code findDueForReminder}). Idempotent per
     * due-day via the {@code reminded_on} stamp.
     */
    @Query("""
            SELECT t FROM TaskEntity t
            WHERE t.status = com.shifa.oms.task.TaskStatus.OPEN
              AND t.dueAt IS NOT NULL
              AND t.dueAt <= :now
              AND (t.remindedOn IS NULL OR t.remindedOn <> :today)
            ORDER BY t.dueAt ASC, t.id ASC
            """)
    List<TaskEntity> findDueForReminder(@Param("now") LocalDateTime now,
                                        @Param("today") java.time.LocalDate today);
}
