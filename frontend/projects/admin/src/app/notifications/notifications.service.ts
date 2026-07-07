import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiClient, PageResponse } from 'core';
import { AdminNotificationItem, UnreadCount } from './notifications.model';

/** Filters + paging for the admin notifications listing (server-side). */
export interface NotificationPageQuery {
  unreadOnly?: boolean | null;
  type?: string | null;
  page?: number;
  size?: number;
  /** `field,dir` sort expression (e.g. "createdAt,desc"). */
  sort?: string | null;
}

/**
 * Data access for the admin Notifications center
 * ({@code /api/admin/notifications}, ADMIN). Calls go through the shared
 * {@link ApiClient}; the auth interceptor attaches the bearer token.
 */
@Injectable({ providedIn: 'root' })
export class NotificationsService {
  private readonly api = inject(ApiClient);

  /** Server-side paginated, filtered notifications. */
  page(query: NotificationPageQuery): Observable<PageResponse<AdminNotificationItem>> {
    const params: Record<string, string | number | boolean> = {
      page: query.page ?? 0,
      size: query.size ?? 20,
    };
    if (query.unreadOnly) {
      params['unreadOnly'] = true;
    }
    if (query.type) {
      params['type'] = query.type;
    }
    if (query.sort) {
      params['sort'] = query.sort;
    }
    return this.api.get<PageResponse<AdminNotificationItem>>('/api/admin/notifications', { params });
  }

  /** The current unread count (for the top-bar bell badge). */
  unreadCount(): Observable<UnreadCount> {
    return this.api.get<UnreadCount>('/api/admin/notifications/unread-count');
  }

  /** Mark a single notification read. */
  markRead(id: number): Observable<void> {
    return this.api.post<void>(`/api/admin/notifications/${id}/read`);
  }

  /** Mark every notification read. */
  markAllRead(): Observable<void> {
    return this.api.post<void>('/api/admin/notifications/read-all');
  }
}
