import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiClient } from 'core';
import { AuthenticatedBlobService } from '../shared/authenticated-blob.service';
import { BackupRun, BackupRunResult } from './backups.model';

/**
 * Data access for the admin database Backups feature ({@code /api/admin/backups},
 * JSON calls go through the shared {@link ApiClient}; the archive download uses
 * the shared authenticated blob transport and browser download helper. Both
 * paths run through the auth interceptor and the backend enforces ADMIN.
 */
@Injectable({ providedIn: 'root' })
export class BackupsService {
  private readonly api = inject(ApiClient);
  private readonly blobs = inject(AuthenticatedBlobService);

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
    return this.blobs.get(`/api/admin/backups/${id}/download`);
  }
}
