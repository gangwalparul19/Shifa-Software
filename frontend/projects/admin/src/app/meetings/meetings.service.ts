import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiClient } from 'core';
import {
  Meeting,
  MeetingOutcomeRequest,
  MeetingStatus,
  ScheduleMeetingRequest,
} from './meeting.model';

/**
 * Data access for CRM meetings (`/api/meetings`, CRM expansion Phase 2,
 * Task 11). Owner scoping (own / team / all) is enforced server-side.
 */
@Injectable({ providedIn: 'root' })
export class MeetingsService {
  private readonly api = inject(ApiClient);

  list(status?: MeetingStatus | null): Observable<Meeting[]> {
    const params: Record<string, string> = {};
    if (status) {
      params['status'] = status;
    }
    return this.api.get<Meeting[]>(
      '/api/meetings',
      Object.keys(params).length ? { params } : undefined,
    );
  }

  schedule(payload: ScheduleMeetingRequest): Observable<Meeting> {
    return this.api.post<Meeting>('/api/meetings', payload);
  }

  reschedule(id: number, payload: ScheduleMeetingRequest): Observable<Meeting> {
    return this.api.put<Meeting>(`/api/meetings/${id}`, payload);
  }

  cancel(id: number): Observable<Meeting> {
    return this.api.post<Meeting>(`/api/meetings/${id}/cancel`, {});
  }

  recordOutcome(id: number, payload: MeetingOutcomeRequest): Observable<Meeting> {
    return this.api.post<Meeting>(`/api/meetings/${id}/outcome`, payload);
  }
}
