import { Injectable, inject } from '@angular/core';
import { Observable, forkJoin, of } from 'rxjs';
import { map } from 'rxjs/operators';
import { ApiClient, PageResponse } from 'core';
import { AdminNotificationItem, UnreadCount } from './notifications.model';
import { NotificationPageQuery } from './notifications.service';

/**
 * Data access for the per-user notification bell against the staff-facing
 * endpoint ({@code /api/notifications}, design §5.2, §6, Req 13.4).
 *
 * <p>Unlike {@link NotificationsService} (which targets the ADMIN-only
 * {@code /api/admin/notifications} console), this service is available to every
 * authenticated staff role and returns exactly the notifications addressed to
 * the caller's role or user id (plus legacy admin broadcasts for admins). The
 * bell uses it so a salesperson, packer, or accountant sees their own alerts —
 * not only admin broadcasts.
 *
 * <p>The staff endpoint has no bulk "read-all"; {@link markAllRead} therefore
 * marks each currently-visible unread item read individually.
 */
@Injectable({ providedIn: 'root' })
export class StaffNotificationsService {
  private readonly api = inject(ApiClient);

  /** Server-side paginated, filtered notifications visible to the current user. */
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
    return this.api.get<PageResponse<AdminNotificationItem>>('/api/notifications', { params });
  }

  /** The current user's unread count (bell badge). */
  unreadCount(): Observable<UnreadCount> {
    return this.api.get<UnreadCount>('/api/notifications/unread-count');
  }

  /** Mark a single notification read (idempotent). */
  markRead(id: number): Observable<void> {
    return this.api.post<void>(`/api/notifications/${id}/read`);
  }

  /**
   * Mark the supplied notifications read. The staff endpoint has no bulk
   * read-all, so this fans out one {@code /read} call per id; a no-op resolves
   * immediately when the list is empty.
   */
  markManyRead(ids: number[]): Observable<unknown> {
    if (ids.length === 0) {
      return of(null);
    }
    return forkJoin(ids.map((id) => this.markRead(id))).pipe(map(() => null));
  }
}
