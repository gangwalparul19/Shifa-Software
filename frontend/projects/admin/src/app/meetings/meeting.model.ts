/** CRM meeting read/write models (CRM expansion Phase 2, Task 11). */

export type MeetingMode = 'CALL' | 'VIDEO' | 'IN_PERSON';
export type MeetingStatus = 'SCHEDULED' | 'DONE' | 'CANCELLED';

export interface Meeting {
  id: number;
  ownerUserId: number;
  title: string;
  leadId?: number | null;
  customerMobile?: string | null;
  startAt: string;
  mode?: MeetingMode | null;
  location?: string | null;
  notes?: string | null;
  status: MeetingStatus;
  outcome?: string | null;
  createdAt: string;
}

export interface ScheduleMeetingRequest {
  title: string;
  leadId?: number | null;
  customerMobile?: string | null;
  startAt: string;
  mode?: MeetingMode | null;
  location?: string | null;
  notes?: string | null;
  ownerUserId?: number | null;
}

export interface MeetingOutcomeRequest {
  outcome: string;
}

export const MEETING_MODES: MeetingMode[] = ['CALL', 'VIDEO', 'IN_PERSON'];

export function meetingModeLabel(m: MeetingMode | null | undefined): string {
  switch (m) {
    case 'CALL':
      return 'Call';
    case 'VIDEO':
      return 'Video';
    case 'IN_PERSON':
      return 'In person';
    default:
      return '—';
  }
}

export function meetingStatusClass(s: MeetingStatus): string {
  switch (s) {
    case 'SCHEDULED':
      return 'badge tone-blue';
    case 'DONE':
      return 'badge tone-green';
    case 'CANCELLED':
      return 'badge tone-grey';
  }
}
