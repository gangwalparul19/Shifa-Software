/**
 * Scheduled customer meetings (CRM expansion Phase 2, Task 11, design &sect;2.3).
 * A meeting is owned by a CRM_User, optionally linked to a lead/customer, with a
 * start time, mode, location, notes, and an outcome (Req 9). Owner-scoped
 * reads/writes; upcoming reminders via {@link com.shifa.oms.meeting.MeetingReminderJob}
 * on the existing outbox + notification rails.
 */
package com.shifa.oms.meeting;
