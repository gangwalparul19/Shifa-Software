package com.shifa.oms.lead;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Spring Data repository for the {@link LeadEntity} aggregate (design
 * &sect;Components).
 *
 * <p>Every finder is role-scoped by an optional {@code ownerUserId} injected from
 * {@link com.shifa.oms.auth.SalespersonScopeResolver} (Req 3.1, 3.2), mirroring
 * {@link com.shifa.oms.order.OrderRepository#findAllScoped(Long)}: a salesperson
 * passes their own id, an admin passes {@code null} to see everything. Scoping is
 * applied in SQL ({@code :ownerUserId IS NULL OR owner_user_id = :ownerUserId})
 * so it cannot be bypassed by request manipulation.
 */
public interface LeadRepository extends JpaRepository<LeadEntity, Long> {

    /** A lead visible to a salesperson only when they own it (Req 3.1); scoped detail. */
    Optional<LeadEntity> findByIdAndOwnerUserId(Long id, Long ownerUserId);

    /**
     * All leads for a scope (no search term), most recent first. A {@code null}
     * {@code ownerUserId} disables scoping (admin/unscoped); a non-null value
     * restricts to that owner's leads (Req 3.1, 3.2).
     */
    @Query(value = """
            SELECT l.* FROM leads l
            WHERE (:ownerUserId IS NULL OR l.owner_user_id = :ownerUserId)
            ORDER BY l.created_at DESC
            """, nativeQuery = true)
    List<LeadEntity> findAllScoped(@Param("ownerUserId") Long ownerUserId);

    /**
     * Role-scoped search over name / mobile with optional status and source
     * filters (Req 3.4). A blank {@code term} disables the text filter; a
     * {@code null} {@code status} / {@code source} disables that filter. Scoping
     * is applied first ({@code null} {@code ownerUserId} = unscoped). {@code status}
     * and {@code source} are matched against the persisted enum names.
     */
    @Query(value = """
            SELECT l.* FROM leads l
            WHERE (:ownerUserId IS NULL OR l.owner_user_id = :ownerUserId)
              AND ( :term IS NULL
                 OR LOWER(l.customer_name) LIKE CONCAT('%', LOWER(:term), '%')
                 OR l.customer_mobile      LIKE CONCAT('%', :term, '%') )
              AND (:status IS NULL OR l.status = :status)
              AND (:source IS NULL OR l.lead_source = :source)
            ORDER BY l.created_at DESC
            """, nativeQuery = true)
    List<LeadEntity> search(@Param("ownerUserId") Long ownerUserId,
                            @Param("term") String term,
                            @Param("status") String status,
                            @Param("source") String source);

    /**
     * The acting user's non-terminal leads whose {@code follow_up_date} is on or
     * before {@code today}, oldest-due first (Req 5.2, 5.4). Leads with no
     * follow-up date are excluded. {@code null} {@code ownerUserId} = unscoped.
     */
    @Query(value = """
            SELECT l.* FROM leads l
            WHERE (:ownerUserId IS NULL OR l.owner_user_id = :ownerUserId)
              AND l.follow_up_date IS NOT NULL
              AND l.follow_up_date <= :today
              AND l.status NOT IN ('WON','LOST')
            ORDER BY l.follow_up_date ASC
            """, nativeQuery = true)
    List<LeadEntity> findDueFollowUps(@Param("ownerUserId") Long ownerUserId,
                                      @Param("today") LocalDate today);

    /**
     * All non-terminal leads (any owner) that are due for a follow-up reminder on
     * {@code today} and have not yet been reminded today (design &sect;Follow-up
     * Reminders): {@code follow_up_date <= today}, {@code status NOT IN (WON,LOST)},
     * and {@code reminded_on} is null or not equal to {@code today}. This is the
     * unscoped source the {@code FollowUpReminderJob} drains, enqueuing one
     * reminder per lead addressed to its owner and stamping {@code reminded_on}
     * so a lead fires at most once per due day.
     */
    @Query(value = """
            SELECT l.* FROM leads l
            WHERE l.follow_up_date IS NOT NULL
              AND l.follow_up_date <= :today
              AND l.status NOT IN ('WON','LOST')
              AND (l.reminded_on IS NULL OR l.reminded_on <> :today)
            ORDER BY l.follow_up_date ASC, l.id ASC
            """, nativeQuery = true)
    List<LeadEntity> findDueForReminder(@Param("today") LocalDate today);

    /**
     * Active-lead counts grouped by {@link LeadStatus} for the pipeline board /
     * snapshot (Req 3.3, 6.3), scoped by owner. Terminal (WON/LOST) leads are
     * excluded — the pipeline shows only leads still in play.
     */
    @Query(value = """
            SELECT l.status AS status, COUNT(*) AS count FROM leads l
            WHERE (:ownerUserId IS NULL OR l.owner_user_id = :ownerUserId)
              AND l.status NOT IN ('WON','LOST')
            GROUP BY l.status
            """, nativeQuery = true)
    List<StatusCount> pipelineCounts(@Param("ownerUserId") Long ownerUserId);

    /**
     * Projection over {@link #pipelineCounts(Long)}: the {@link LeadStatus} enum
     * name and the number of active leads currently in that status.
     */
    interface StatusCount {
        String getStatus();

        long getCount();
    }
}
