/** Severity of an admin notification, driving its accent colour. */
export type NotificationSeverity = 'info' | 'success' | 'warning' | 'danger';

/**
 * A persisted admin notification returned by {@code /api/admin/notifications}.
 * Distinct from the transient in-memory SSE feed ({@code AdminNotification});
 * these are durable, filterable and individually markable as read.
 */
export interface AdminNotificationItem {
  id: number;
  type: string;
  title: string;
  detail: string;
  severity: NotificationSeverity;
  orderId?: number | null;
  orderCode?: string | null;
  read: boolean;
  createdAt?: string | null;
  readAt?: string | null;
}

/** Result of {@code GET /api/admin/notifications/unread-count}. */
export interface UnreadCount {
  unreadCount: number;
}
