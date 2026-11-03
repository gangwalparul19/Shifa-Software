import { Injectable, inject } from '@angular/core';
import { ApiClient } from 'core';
import { Observable } from 'rxjs';

/**
 * One line in the admin-report recipient list.
 *
 * `ADMIN_USER` is an active admin read from the user list (it may have no email on the profile),
 * `ADDED` is an address someone added under Settings → Email, and `SERVER` is an address set on the
 * server (read only here).
 */
export interface ReportRecipientRow {
  kind: 'ADMIN_USER' | 'ADDED' | 'SERVER';
  userId: number | null;
  extraId: number | null;
  name: string;
  /** Null when an admin has no email on their profile. */
  email: string | null;
  /** False for a paused added address. */
  active: boolean;
  /** Whether the scheduled morning report is sent to this line. */
  scheduled: boolean;
  /** Whether this line can be chosen in "Send now". */
  sendable: boolean;
  /** Why the line is not used, when it is not. */
  note: string | null;
}

export interface ReportRecipientOverview {
  rows: ReportRecipientRow[];
  /** Whether the scheduled report includes admin users automatically. */
  adminsIncluded: boolean;
  /** Whether the server sends the morning report by itself. */
  scheduledSendEnabled: boolean;
  scheduledCount: number;
}

export interface ReportSendFailure {
  email: string;
  reason: string;
}

export interface ReportSendResult {
  date: string;
  orderCount: number;
  totalSales: number;
  requested: number;
  sent: number;
  failures: ReportSendFailure[];
}

/** Data access for Settings → Email (who receives the admin reports, and "Send now"). */
@Injectable({ providedIn: 'root' })
export class ReportRecipientsService {
  private readonly api = inject(ApiClient);

  list(): Observable<ReportRecipientOverview> {
    return this.api.get<ReportRecipientOverview>('/api/admin/report-recipients');
  }

  /** Adds an address that will receive every report from now on. */
  add(email: string, name: string): Observable<ReportRecipientOverview> {
    return this.api.post<ReportRecipientOverview>('/api/admin/report-recipients', { email, name });
  }

  setActive(id: number, active: boolean): Observable<ReportRecipientOverview> {
    return this.api.put<ReportRecipientOverview>(`/api/admin/report-recipients/${id}/active`, { active });
  }

  remove(id: number): Observable<ReportRecipientOverview> {
    return this.api.delete<ReportRecipientOverview>(`/api/admin/report-recipients/${id}`);
  }

  /** Sends the morning report now to the chosen addresses (date is yyyy-MM-dd; blank = yesterday). */
  send(date: string | null, emails: string[]): Observable<ReportSendResult> {
    return this.api.post<ReportSendResult>('/api/admin/report-recipients/send', { date, emails });
  }
}
