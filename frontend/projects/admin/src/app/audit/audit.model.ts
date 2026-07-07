/**
 * A single audit-log entry returned by {@code GET /api/admin/audit} (ADMIN).
 * Mirrors the backend audit DTO; the listing is newest-first.
 */
export interface AuditEntry {
  id: number;
  actorUserId?: number | null;
  actorUsername?: string | null;
  action: string;
  entityType: string;
  entityId?: string | number | null;
  summary?: string | null;
  createdAt?: string | null;
}
