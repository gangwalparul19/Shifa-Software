/**
 * Client-side models for the database Backups feature, mirroring the backend
 * {@code com.shifa.oms.platform.backup} DTOs (Req 24.1, 24.2).
 */

/**
 * One recorded backup run, from {@code GET /api/admin/backups}
 * (backend {@code BackupController.BackupRunResponse}). Ordered most-recent
 * first by the backend.
 */
export interface BackupRun {
  id: number;
  /** ISO timestamp the run started. */
  startedAt?: string;
  /** ISO timestamp the run finished (absent while running / on abrupt failure). */
  finishedAt?: string | null;
  /** Backend status text, e.g. RUNNING / SUCCESS / FAILED. */
  status: string;
  /** Storage key of the uploaded archive on success. */
  objectKey?: string | null;
  /** Failure detail when the run failed. */
  error?: string | null;
}

/**
 * Outcome of triggering a backup now, from {@code POST /api/admin/backups/run}
 * (backend {@code BackupService.BackupResult}).
 */
export interface BackupRunResult {
  success: boolean;
  backupRunId: number;
  objectKey?: string | null;
  error?: string | null;
}
