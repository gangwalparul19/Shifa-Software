/**
 * Read models for the unified CRM activity log (CRM expansion Phase 2, Task 9).
 * Mirror the backend `activity` DTOs.
 */

/** One activity-log row (`crm_activities`). */
export interface ActivityEntry {
  id: number;
  activityType: string;
  leadId?: number | null;
  customerMobile?: string | null;
  actorUserId?: number | null;
  actorName?: string | null;
  summary: string;
  detail?: string | null;
  createdAt: string;
}

/** One merged timeline item (activity row or order-history row). */
export interface TimelineItem {
  source: 'ACTIVITY' | 'ORDER';
  type: string;
  summary: string;
  detail?: string | null;
  actorName?: string | null;
  at: string;
}

/** Request body for POST /api/activities/log. */
export interface LogActivityRequest {
  type: string;
  leadId?: number | null;
  customerMobile?: string | null;
  summary: string;
  detail?: string | null;
}

/** A friendly label + Tabler icon for an activity type. */
export function activityMeta(type: string): { label: string; icon: string } {
  switch (type) {
    case 'STAGE_CHANGE':
      return { label: 'Stage change', icon: 'ti-arrow-right-circle' };
    case 'TAG_ADDED':
      return { label: 'Tag added', icon: 'ti-tag' };
    case 'TAG_REMOVED':
      return { label: 'Tag removed', icon: 'ti-tag-off' };
    case 'NOTE':
      return { label: 'Note', icon: 'ti-note' };
    case 'ASSIGNED':
      return { label: 'Assigned', icon: 'ti-user-share' };
    case 'FOLLOW_UP_SET':
      return { label: 'Follow-up set', icon: 'ti-calendar-event' };
    case 'FOLLOW_UP_MISSED':
      return { label: 'Follow-up missed', icon: 'ti-calendar-x' };
    case 'TASK_CREATED':
      return { label: 'Task created', icon: 'ti-checkbox' };
    case 'TASK_UPDATED':
      return { label: 'Task updated', icon: 'ti-pencil' };
    case 'TASK_DONE':
      return { label: 'Task done', icon: 'ti-circle-check' };
    case 'MEETING_SCHEDULED':
      return { label: 'Meeting scheduled', icon: 'ti-calendar-plus' };
    case 'MEETING_RESCHEDULED':
      return { label: 'Meeting rescheduled', icon: 'ti-calendar' };
    case 'MEETING_CANCELLED':
      return { label: 'Meeting cancelled', icon: 'ti-calendar-off' };
    case 'MEETING_OUTCOME':
      return { label: 'Meeting outcome', icon: 'ti-calendar-check' };
    case 'CALL':
      return { label: 'Call', icon: 'ti-phone' };
    case 'WHATSAPP':
      return { label: 'WhatsApp', icon: 'ti-brand-whatsapp' };
    default:
      if (type?.startsWith('ORDER_')) {
        return { label: 'Order', icon: 'ti-shopping-cart' };
      }
      return { label: type, icon: 'ti-circle' };
  }
}
