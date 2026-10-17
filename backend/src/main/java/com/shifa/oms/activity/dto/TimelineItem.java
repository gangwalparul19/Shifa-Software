package com.shifa.oms.activity.dto;

import java.time.LocalDateTime;

/**
 * One item of a merged timeline (CRM expansion Phase 2, Task 9; Req 11.4). The
 * per-customer timeline is the union of {@code crm_activities} for a mobile and
 * that customer's order history (Customer 360), so both are projected to this
 * common shape and ordered newest-first.
 *
 * @param source {@code ACTIVITY} for a crm_activities row, {@code ORDER} for an
 *               order-history row
 */
public record TimelineItem(
        String source,
        String type,
        String summary,
        String detail,
        String actorName,
        LocalDateTime at) {

    public static TimelineItem activity(ActivityResponse a) {
        return new TimelineItem("ACTIVITY", a.activityType(), a.summary(), a.detail(),
                a.actorName(), a.createdAt());
    }
}
