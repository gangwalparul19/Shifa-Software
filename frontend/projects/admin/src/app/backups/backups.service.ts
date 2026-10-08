import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiClient } from 'core';
import { BackupRun, BackupRunResult } from './backups.model';

/**
 * Data access for the admin database Backups feature ({@code /api/admin/backups},
 * ADMIN only). JSON calls go through the shared {@link ApiClient}; the archive
 * download uses {@link HttpClient} directly with a {@code blob} response. Both
 * paths run through the auth interceptor (bearer token) and the backend enforces
 * the ADMIN role.
 */
@Injectable({ providedIn: 'root' })
export class BackupsService {
  private readonly api = inject(ApiClient);
  private readonly http = inject(HttpClient);

  /** Recent backup runs, most recent first (backend orders them). */
  history(): Observable<BackupRun[]> {
    return this.api.get<BackupRun[]>('/api/admin/backups');
  }

  /** Trigger a backup immediately; returns its outcome. */
  run(): Observable<BackupRunResult> {
    return this.api.post<BackupRunResult>('/api/admin/backups/run', {});
  }

  /** Download a successful run's archive (gzip) as a Blob for the browser to save. */
  download(id: number): Observable<Blob> {
    return this.http.get(this.api.url(`/api/admin/backups/${id}/download`), {
      responseType: 'blob',
    });
  }
}
