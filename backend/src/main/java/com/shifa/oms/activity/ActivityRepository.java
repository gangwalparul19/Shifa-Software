package com.shifa.oms.activity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Spring Data repository over the unified {@link ActivityEntry} log (CRM
 * expansion Phase 2, Task 9). Reads are newest-first for the per-lead and
 * per-customer timelines (Req 11.2); {@link #findByLeadIdAndCreatedAtAfter}
 * backs the missed-follow-up "acted on since the reminder?" check (Req 10.2).
 */
public interface ActivityRepository extends JpaRepository<ActivityEntry, Long> {

    /** A lead's activity feed, newest first (Req 11.2). */
    List<ActivityEntry> findByLeadIdOrderByCreatedAtDesc(Long leadId);

    /** A customer's activity feed, keyed by mobile, newest first (Req 11.2, 11.4). */
    List<ActivityEntry> findByCustomerMobileOrderByCreatedAtDesc(String customerMobile);

    /**
     * Whether a lead has any activity after {@code since} (Req 10.2): the signal
     * that a due, reminded follow-up was "acted on" within the grace window.
     */
    boolean existsByLeadIdAndCreatedAtAfter(Long leadId, LocalDateTime since);
}
