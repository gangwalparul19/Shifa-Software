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
  | 'PACKAGING'
  | 'LABEL_GENERATED'
  | 'AWAITING_HANDOVER'
  | 'AWAITING_DISPATCH'
  | 'IN_TRANSIT'
  | 'COMPLETED'
  | 'CANCELLED'
  | 'FAILED_RETURNED';

/** A single lifecycle group: its server key, display label, and member statuses. */
export interface OrderStatusGroupDef {
  key: OrderStatusGroupKey;
  label: string;
  statuses: OrderStatus[];
}

/** The ordered lifecycle groups (a complete, non-overlapping partition). */
export const ORDER_STATUS_GROUPS: readonly OrderStatusGroupDef[] = [
  { key: 'PENDING_APPROVAL', label: 'Pending Approval', statuses: [OrderStatus.PENDING_ADMIN_APPROVAL] },
  { key: 'PACKAGING', label: 'Packaging', statuses: [OrderStatus.APPROVED] },
  { key: 'LABEL_GENERATED', label: 'Label Generated', statuses: [OrderStatus.LABEL_GENERATED] },
  { key: 'AWAITING_HANDOVER', label: 'Awaiting Handover', statuses: [OrderStatus.PACKED] },
  { key: 'AWAITING_DISPATCH', label: 'Awaiting Dispatch', statuses: [OrderStatus.HANDED_TO_DELIVERY] },
  {
    key: 'IN_TRANSIT',
    label: 'In Transit',
    statuses: [
      OrderStatus.COURIER_ASSIGNED,
      OrderStatus.DISPATCHED,
      OrderStatus.IN_TRANSIT,
      OrderStatus.OUT_FOR_DELIVERY,
    ],
  },
  {
    key: 'COMPLETED',
    label: 'Completed',
    statuses: [OrderStatus.DELIVERED, OrderStatus.COD_COLLECTED, OrderStatus.CLOSED],
  },
  { key: 'CANCELLED', label: 'Cancelled', statuses: [OrderStatus.REJECTED, OrderStatus.CANCELLED] },
  {
    key: 'FAILED_RETURNED',
    label: 'Failed / Returned',
    statuses: [
      OrderStatus.CUSTOMER_REJECTED,
      OrderStatus.DELIVERY_FAILED,
      OrderStatus.RTO,
      OrderStatus.COURIER_LOST,
    ],
  },
];

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
