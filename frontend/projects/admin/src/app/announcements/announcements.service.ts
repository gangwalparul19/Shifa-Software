import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiClient } from 'core';
import { Announcement, CreateAnnouncement } from './announcements.model';

/**
 * Data access for staff announcement banners (FEATURE-ROADMAP §8.4).
 *
 * <p>{@link active} is open to all staff (drives the app-wide banner); the
 * management calls hit the ADMIN-only {@code /api/admin/announcements} surface.
 */
@Injectable({ providedIn: 'root' })
export class AnnouncementsService {
  private readonly api = inject(ApiClient);

  /** Active announcements shown to the current staff member. */
  active(): Observable<Announcement[]> {
    return this.api.get<Announcement[]>('/api/announcements');
  }

  /** Every announcement (admin management view). */
  listAll(): Observable<Announcement[]> {
    return this.api.get<Announcement[]>('/api/admin/announcements');
  }

  /** Posts a new (active) announcement. */
  create(payload: CreateAnnouncement): Observable<Announcement> {
    return this.api.post<Announcement>('/api/admin/announcements', payload);
  }

  /** Activates or deactivates an announcement. */
  setActive(id: number, value: boolean): Observable<Announcement> {
    return this.api.post<Announcement>(
      `/api/admin/announcements/${id}/active`,
      null,
      { params: { value } },
    );
  }

  /** Permanently deletes an announcement. */
  remove(id: number): Observable<void> {
    return this.api.delete<void>(`/api/admin/announcements/${id}`);
  }
}
