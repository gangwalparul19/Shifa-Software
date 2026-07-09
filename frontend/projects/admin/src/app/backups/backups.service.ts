import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiClient } from 'core';
import { BackupRun, BackupRunResult } from './backups.model';

/**
 * Data access for the admin database Backups feature ({@code /api/admin/backups},
 * ADMIN only). Calls go through the shared {@link ApiClient} so the auth
 * interceptor attaches the bearer token and the backend enforces the role.
 */
@Injectable({ providedIn: 'root' })
export class BackupsService {
  private readonly api = inject(ApiClient);

  /** Recent backup runs, most recent first (backend orders them). */
  history(): Observable<BackupRun[]> {
    return this.api.get<BackupRun[]>('/api/admin/backups');
  }

  /** Trigger a backup immediately; returns its outcome. */
  run(): Observable<BackupRunResult> {
    return this.api.post<BackupRunResult>('/api/admin/backups/run', {});
  }
}
