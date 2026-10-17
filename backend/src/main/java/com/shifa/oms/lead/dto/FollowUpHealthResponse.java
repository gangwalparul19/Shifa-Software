package com.shifa.oms.lead.dto;

import java.util.List;

/**
 * The cross-user follow-up health view for the admin monitor (CRM expansion
 * Phase 2, Task 12; Req 10.3): counts and lists of upcoming, due/overdue, and
 * missed follow-ups. {@code upcoming} = leads with a future follow-up date;
 * {@code due} = non-terminal leads due on/before today and not yet missed;
 * {@code missed} = leads with {@code follow_up_missed_at} set.
 */
public record FollowUpHealthResponse(
        long upcomingCount,
        long dueCount,
        long missedCount,
        List<LeadSummaryResponse> due,
        List<LeadSummaryResponse> missed) {
}
