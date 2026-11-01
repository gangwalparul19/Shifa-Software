/**
 * Title-cases an enum-style code for display ("ORDER_STATUS_CHANGED" → "Order
 * Status Changed", "LOW_STOCK" → "Low Stock").
 *
 * <p>Use this for vocabularies that have no curated business labels (audit action
 * names, notification types, stock states). Order statuses are different: they
 * carry curated labels ("Tracking ID Assigned", "In Transit"), so use
 * {@code humanizeStatus} from {@code status-badge.component} for those.
 */
export function titleCaseCode(value: string | null | undefined): string {
  return (value ?? '')
    .replaceAll('_', ' ')
    .toLowerCase()
    .replace(/\b\w/g, (c) => c.toUpperCase());
}
