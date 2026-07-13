/** Severity palette shared with notifications. */
export type AnnouncementSeverity = 'info' | 'success' | 'warning' | 'danger';

/** A staff announcement banner (FEATURE-ROADMAP §8.4). */
export interface Announcement {
  id: number;
  message: string;
  severity: AnnouncementSeverity | string;
  active: boolean;
  createdByName?: string | null;
  createdAt: string;
}

/** Payload to post a new announcement. */
export interface CreateAnnouncement {
  message: string;
  severity: AnnouncementSeverity;
}

/** Tabler alert class for an announcement severity. */
export function announcementAlertClass(severity: AnnouncementSeverity | string): string {
  switch (severity) {
    case 'success':
      return 'alert-success';
    case 'warning':
      return 'alert-warning';
    case 'danger':
      return 'alert-danger';
    default:
      return 'alert-info';
  }
}

/** Tabler icon for an announcement severity. */
export function announcementIcon(severity: AnnouncementSeverity | string): string {
  switch (severity) {
    case 'success':
      return 'ti-circle-check';
    case 'warning':
      return 'ti-alert-triangle';
    case 'danger':
      return 'ti-alert-octagon';
    default:
      return 'ti-speakerphone';
  }
}
