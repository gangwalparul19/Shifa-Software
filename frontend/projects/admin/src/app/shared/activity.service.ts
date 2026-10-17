import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiClient } from 'core';
import { ActivityEntry, LogActivityRequest, TimelineItem } from './activity.model';

/**
 * Data access for the unified CRM activity timeline (CRM expansion Phase 2,
 * Task 9). The per-lead timeline is on `/api/leads/{id}/activity`; the
 * per-customer merged timeline on `/api/admin/customers/{mobile}/activity`; and
 * a lightweight CALL/WHATSAPP log on `/api/activities/log`.
 */
@Injectable({ providedIn: 'root' })
export class ActivityService {
  private readonly api = inject(ApiClient);

  /** A lead's activity timeline, newest first (Req 11.2). */
  forLead(leadId: number): Observable<ActivityEntry[]> {
    return this.api.get<ActivityEntry[]>(`/api/leads/${leadId}/activity`);
  }

  /** A customer's merged timeline (activities + order history), newest first (Req 11.4). */
  forCustomer(mobile: string): Observable<TimelineItem[]> {
    return this.api.get<TimelineItem[]>(`/api/admin/customers/${encodeURIComponent(mobile)}/activity`);
  }

  /** Log a CRM-initiated touch (CALL / WHATSAPP / …) on a lead/customer (Req 11.1). */
  log(payload: LogActivityRequest): Observable<ActivityEntry> {
    return this.api.post<ActivityEntry>('/api/activities/log', payload);
  }
}
