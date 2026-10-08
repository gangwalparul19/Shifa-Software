/**
 * Parses an audit summary into structured before/after field changes
 * (audit-diff-viewer enhancement).
 *
 * <p>Several audit actions (notably {@code ORDER_UPDATED}) record a concise
 * field diff in the free-text {@code summary} as a run of
 * {@code Label: 'old' → 'new'} entries joined by {@code "; "}. This parses that
 * shape into typed rows so the UI can render a readable old→new table instead of
 * one long sentence. Summaries that don't match the pattern return no rows (the
 * caller falls back to showing the raw summary).
 */

/** A single parsed field change: what changed, from what, to what. */
export interface AuditFieldChange {
  field: string;
  before: string;
  after: string;
}

// Matches "Label: 'old' → 'new'" (the arrow may be → or ->), tolerant of spacing.
const CHANGE_RE = /([^:;]+):\s*'([^']*)'\s*(?:→|->)\s*'([^']*)'/g;

/**
 * Extracts the field changes encoded in an audit summary, or an empty array when
 * the summary carries no {@code 'old' → 'new'} pairs.
 */
export function parseAuditDiff(summary: string | null | undefined): AuditFieldChange[] {
  if (!summary) {
    return [];
  }
  const changes: AuditFieldChange[] = [];
  let m: RegExpExecArray | null;
  CHANGE_RE.lastIndex = 0;
  while ((m = CHANGE_RE.exec(summary)) !== null) {
    changes.push({
      field: m[1].trim().replace(/^[-–—•]\s*/, ''),
      before: m[2],
      after: m[3],
    });
  }
  return changes;
}

/** Whether a summary carries a structured field diff worth rendering as a table. */
export function hasAuditDiff(summary: string | null | undefined): boolean {
  return parseAuditDiff(summary).length > 0;
}
