package com.shifa.oms.activity.dto;

import com.shifa.oms.activity.ActivityEntry;

import java.time.LocalDateTime;

/**
 * Read-model projection of one {@link ActivityEntry} for the timeline APIs
 * (CRM expansion Phase 2, Task 9; Req 11.2). A plain record so the controller
 * stays free of entity concerns.
 */
public record ActivityResponse(
        Long id,
        String activityType,
        Long leadId,
        String customerMobile,
        Long actorUserId,
        String actorName,
        String summary,
        String detail,
        LocalDateTime createdAt) {

    public static ActivityResponse from(ActivityEntry e) {
        return new ActivityResponse(
                e.getId(), e.getActivityType(), e.getLeadId(), e.getCustomerMobile(),
                e.getActorUserId(), e.getActorName(), e.getSummary(), e.getDetail(),
                e.getCreatedAt());
    }
}
