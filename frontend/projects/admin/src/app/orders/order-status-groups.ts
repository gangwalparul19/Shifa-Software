import { OrderStatus } from 'core';

/**
 * Business-facing lifecycle groups that club the many raw {@link OrderStatus}
 * values into a short, friendly set of stages for the Orders page filter
 * (product feedback: mostly less-technical salespeople found ~18 raw statuses
 * overwhelming). Mirrors the backend {@code OrderStatusGroup} enum 1:1 — the
 * {@link OrderStatusGroupKey} values are sent as {@code ?statusGroup=} and the
 * server expands them to {@code orderStatus IN (members)}.
 *
 * <p>The member statuses are only used client-side to map a legacy raw-status
 * deep link / saved view back onto its group; the actual filtering is done
 * server-side so it is correct across pagination.
 */
export type OrderStatusGroupKey =
  | 'PENDING_APPROVAL'
  | 'PROCESSING'
  | 'SHIPPED'
  | 'DELIVERED'
  | 'FAILED_RETURNED'
  | 'CANCELLED';

/** A single lifecycle group: its server key, display label, and member statuses. */
export interface OrderStatusGroupDef {
  key: OrderStatusGroupKey;
  label: string;
  statuses: OrderStatus[];
}

/**
 * The ordered lifecycle groups (a complete, non-overlapping partition), collapsed
 * to a QuikShip-aligned set. With QuikShip handling fulfilment, the old
 * Packaging / Label Generated / Awaiting Handover / Awaiting Dispatch stages are
 * skipped in seconds, so they are folded into a single "Processing" stage to keep
 * the salesperson's view simple.
 */
export const ORDER_STATUS_GROUPS: readonly OrderStatusGroupDef[] = [
  { key: 'PENDING_APPROVAL', label: 'Pending Approval', statuses: [OrderStatus.PENDING_ADMIN_APPROVAL] },
  {
    key: 'PROCESSING',
    label: 'Processing',
    statuses: [
      OrderStatus.APPROVED,
      OrderStatus.LABEL_GENERATED,
      OrderStatus.PACKED,
      OrderStatus.HANDED_TO_DELIVERY,
    ],
  },
  {
    key: 'SHIPPED',
    label: 'Shipped',
    statuses: [
      OrderStatus.COURIER_ASSIGNED,
      OrderStatus.DISPATCHED,
      OrderStatus.IN_TRANSIT,
      OrderStatus.OUT_FOR_DELIVERY,
    ],
  },
  {
    key: 'DELIVERED',
    label: 'Delivered',
    statuses: [OrderStatus.DELIVERED, OrderStatus.COD_COLLECTED, OrderStatus.CLOSED],
  },
  {
    key: 'FAILED_RETURNED',
    label: 'Returned / Failed',
    statuses: [
      OrderStatus.CUSTOMER_REJECTED,
      OrderStatus.DELIVERY_FAILED,
      OrderStatus.RTO,
      OrderStatus.REDISPATCH,
    ],
  },
  { key: 'CANCELLED', label: 'Cancelled', statuses: [OrderStatus.REJECTED, OrderStatus.CANCELLED] },
];

/**
 * Maps a pre-collapse group key (from a stale saved view / deep link) onto the
 * current key, so old bookmarks keep filtering correctly. Returns the input
 * unchanged when it is already a current key or unrecognised.
 */
export function normalizeGroupKey(key: string | null | undefined): OrderStatusGroupKey | '' {
  if (!key) {
    return '';
  }
  switch (key.toUpperCase()) {
    case 'PENDING_APPROVAL':
      return 'PENDING_APPROVAL';
    case 'PROCESSING':
    case 'PACKAGING':
    case 'LABEL_GENERATED':
    case 'AWAITING_HANDOVER':
    case 'AWAITING_DISPATCH':
      return 'PROCESSING';
    case 'SHIPPED':
    case 'IN_TRANSIT':
      return 'SHIPPED';
    case 'DELIVERED':
    case 'COMPLETED':
      return 'DELIVERED';
    case 'FAILED_RETURNED':
      return 'FAILED_RETURNED';
    case 'CANCELLED':
      return 'CANCELLED';
    default:
      return '';
  }
}

/**
 * The friendly business-stage label for a raw order status (e.g. COURIER_ASSIGNED
 * → "Shipped"), used to show a simplified status to salespeople. Falls back to the
 * raw status when it has no group.
 */
export function stageLabelForStatus(status: string | null | undefined): string {
  if (!status) {
    return '';
  }
  const needle = String(status).toUpperCase();
  const found = ORDER_STATUS_GROUPS.find((g) =>
    g.statuses.some((s) => String(s).toUpperCase() === needle),
  );
  return found ? found.label : String(status);
}

/**
 * Maps a raw order status (in any case) to its group key, or '' if none. Used to
 * translate a legacy {@code ?status=} deep link or a saved view that stored a raw
 * status onto the grouped filter control.
 */
export function groupForStatus(status: string | null | undefined): OrderStatusGroupKey | '' {
  if (!status) {
    return '';
  }
  const needle = status.toUpperCase();
  const found = ORDER_STATUS_GROUPS.find((g) =>
    g.statuses.some((s) => String(s).toUpperCase() === needle),
  );
  return found ? found.key : '';
}
