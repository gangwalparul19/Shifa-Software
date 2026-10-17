package com.shifa.oms.activity;

/**
 * The canonical activity-type discriminators written to {@code crm_activities}
 * (CRM expansion Phase 2, Task 9; Req 11.1). Centralising the strings keeps the
 * timeline vocabulary consistent across the writers (lead / task / meeting /
 * follow-up / frontend call & WhatsApp logs).
 */
public final class ActivityTypes {

    private ActivityTypes() {
    }

    // Lead touches (Req 11.1).
    public static final String STAGE_CHANGE = "STAGE_CHANGE";
    public static final String TAG_ADDED = "TAG_ADDED";
    public static final String TAG_REMOVED = "TAG_REMOVED";
    public static final String NOTE = "NOTE";
    public static final String ASSIGNED = "ASSIGNED";
    public static final String FOLLOW_UP_SET = "FOLLOW_UP_SET";
    public static final String FOLLOW_UP_MISSED = "FOLLOW_UP_MISSED";

    // Task touches (Req 8, 11.1).
    public static final String TASK_CREATED = "TASK_CREATED";
    public static final String TASK_UPDATED = "TASK_UPDATED";
    public static final String TASK_DONE = "TASK_DONE";
    public static final String TASK_CANCELLED = "TASK_CANCELLED";

    // Meeting touches (Req 9, 11.1).
    public static final String MEETING_SCHEDULED = "MEETING_SCHEDULED";
    public static final String MEETING_RESCHEDULED = "MEETING_RESCHEDULED";
    public static final String MEETING_CANCELLED = "MEETING_CANCELLED";
    public static final String MEETING_OUTCOME = "MEETING_OUTCOME";
    public static final String MEETING_NO_SHOW = "MEETING_NO_SHOW";

    // Customer-directed touches initiated from the CRM (Req 11.1).
    public static final String CALL = "CALL";
    public static final String WHATSAPP = "WHATSAPP";
}
