import { OrderStatus } from 'core';
import { formatInr } from '../shared/inr.pipe';
import { humanizeStatus } from '../shared/status-badge.component';
import { delhiveryTrackUrl } from '../shared/tracking-url.util';
import { isOrderInHouse, isOrderStorePickup } from './order-permissions';
import { OrderStatusGroupKey, groupForStatus } from './order-status-groups';
import { LEAD_SOURCE_OPTIONS, RTO_REASON_LABELS, RtoReasonValue } from './orders.model';

/**
 * Pure display helpers for the Orders screens: labels, badge classes, links and
 * small formatting rules that depend only on their arguments.
 *
 * <p>Nothing here reads component state, signals, or services, so the same rule
 * can be used by any Orders view. The components keep thin methods with the same
 * names for their templates and delegate to these functions.
 */

// --- Payment verification / rejection / RTO labels ---------------------------

/** Human label for the payment authenticity-verification pill (product-audit §4.4). */
export function paymentVerificationLabel(status: string | null | undefined): string {
  switch (status) {
    case 'VERIFIED':
      return 'Payment verified';
    case 'REJECTED':
      return 'Payment rejected';
    case 'PENDING':
      return 'Awaiting verification';
    default:
      return '';
  }
}

/** Tabler badge tone for the payment-verification pill. */
export function paymentVerificationClass(status: string | null | undefined): string {
  switch (status) {
    case 'VERIFIED':
      return 'bg-green-lt';
    case 'REJECTED':
      return 'bg-red-lt';
    case 'PENDING':
      return 'bg-yellow-lt';
    default:
      return 'bg-secondary-lt';
  }
}

/** Human label for a manually-marked RTO reason (label redesign feature). */
export function rtoReasonLabel(reason: RtoReasonValue | null | undefined): string {
  return reason ? RTO_REASON_LABELS[reason] ?? reason : '';
}

/** Human label for a categorized rejection reason (rejection-status feature). */
export function rejectReasonLabel(reason: string | null | undefined): string {
  switch (reason) {
    case 'RATE_ISSUE':
      return 'Rate Issue';
    case 'ADDRESS_PINCODE_ISSUE':
      return 'Address / Pincode Issue';
    case 'PAYMENT_ISSUE':
      return 'Payment Issue';
    case 'OTHER':
      return 'Other';
    default:
      return '';
  }
}

// --- Status pills and stage badges -------------------------------------------

/**
 * Colour buckets for the status pill in the detail/mobile views (green =
 * successful terminal, red = cancelled/failed/returned, amber = everything
 * still in flight). Distinct from the filter groups — this is display only.
 */
const PILL_COMPLETED: OrderStatus[] = [
  OrderStatus.DELIVERED,
  OrderStatus.COD_COLLECTED,
  OrderStatus.CLOSED,
];
const PILL_BAD: OrderStatus[] = [
  OrderStatus.REJECTED,
  OrderStatus.CANCELLED,
  OrderStatus.CUSTOMER_REJECTED,
  OrderStatus.DELIVERY_FAILED,
  OrderStatus.RTO,
  OrderStatus.REDISPATCH,
];

/**
 * Coarse colour group for the status pill: green = completed/delivered,
 * red = cancelled/rejected/failed, amber = everything still in flight
 * (processing steps + pending approval).
 */
export function statusPillClass(status: OrderStatus): string {
  if (PILL_COMPLETED.includes(status)) {
    return 'is-green';
  }
  if (PILL_BAD.includes(status)) {
    return 'is-red';
  }
  return 'is-amber';
}

/** Tabler badge class for the friendly stage label, coloured by lifecycle stage. */
export function stageBadgeClass(status: string | null | undefined): string {
  switch (groupForStatus(status)) {
    case 'DELIVERED':
      return 'badge bg-green-lt';
    case 'SHIPPED':
      return 'badge bg-blue-lt';
    case 'PROCESSING':
      return 'badge bg-cyan-lt';
    case 'FAILED_RETURNED':
      return 'badge bg-red-lt';
    case 'CANCELLED':
      return 'badge bg-secondary-lt';
    default:
      return 'badge bg-yellow-lt';
  }
}

// --- QuikShipX ---------------------------------------------------------------

/** Whether a QuikShipX status indicates a permanent shipping failure (needs re-route). */
export function isQuikShipFailed(status: string | null | undefined): boolean {
  return (status ?? '').toLowerCase().includes('fail');
}

/** Tabler badge tone for a QuikShipX status label (colour by lifecycle stage). */
export function quikShipBadgeClass(status: string | null | undefined): string {
  const s = (status ?? '').toLowerCase();
  // A permanent QuikShipX failure ("Shipping Failed") must read as a red alarm
  // so the admin/packer spots it at a glance and re-routes to in-house.
  if (s.includes('fail')) {
    return 'tone-red';
  }
  if (s.includes('deliver')) {
    return 'tone-green';
  }
  if (s.includes('return') || s.includes('lost') || s.includes('cancel')) {
    return 'tone-red';
  }
  if (s.includes('transit') || s.includes('out for') || s.includes('pickup')) {
    return 'tone-blue';
  }
  if (s.includes('tracking') || s.includes('label') || s.includes('confirm')) {
    return 'tone-blue';
  }
  return 'tone-amber';
}

/** The QuikShipX fields shown in the Orders-list chip tooltip. */
export interface QuikShipTitleSource {
  readonly quikShipXStatus?: string | null;
  readonly quikShipXOrderId?: string | null;
  readonly quikShipXAwb?: string | null;
}

/** Hover title for the Orders-list QuikShipX chip: status + order id + AWB. */
export function quikShipTitle(order: QuikShipTitleSource): string {
  const parts = ['QuikShipX ' + (order.quikShipXStatus ?? '')];
  if (order.quikShipXOrderId) {
    parts.push('order #' + order.quikShipXOrderId);
  }
  if (order.quikShipXAwb) {
    parts.push('AWB ' + order.quikShipXAwb);
  }
  return parts.join(' · ');
}

// --- Tracking ----------------------------------------------------------------

/** The tracking fields of an order. */
export interface TrackableOrder {
  readonly trackingUrl?: string | null;
  readonly awb?: string | null;
}

/**
 * The best tracking URL for an order (in-house delivery-partner feature): prefers
 * the vendor-provided direct link the backend supplies on {@code trackingUrl} (a
 * local in-house partner's ready-made page), and otherwise falls back to the
 * Delhivery page built from the AWB (QuikShipX ships via Delhivery). Null when
 * there is nothing to track.
 */
export function orderTrackUrl(order: TrackableOrder | null | undefined): string | null {
  if (!order) {
    return null;
  }
  const direct = (order.trackingUrl ?? '').trim();
  if (direct) {
    return direct;
  }
  return delhiveryTrackUrl(order.awb);
}

/** True when an order can be tracked (has a vendor link or an AWB). */
export function isOrderTrackable(order: TrackableOrder | null | undefined): boolean {
  return !!orderTrackUrl(order);
}

// --- Source / courier partner columns ----------------------------------------

/** Business label for the order source column: Sales / Store / Shopify. */
export function sourceLabel(source: string | null | undefined): string {
  switch (source) {
    case 'SHOPIFY':
      return 'Shopify';
    case 'STORE':
      return 'Store';
    case 'STOREFRONT':
      return 'Storefront';
    case 'SALESPERSON':
      return 'Sales';
    default:
      return source ? humanizeStatus(source) : '—';
  }
}

/** Tabler icon for the order source (non-Shopify sources, which use a logo). */
export function sourceIcon(source: string | null | undefined): string {
  switch (source) {
    case 'STORE':
      return 'ti-building-store';
    case 'STOREFRONT':
      return 'ti-building-store';
    case 'SALESPERSON':
      return 'ti-user';
    default:
      return 'ti-circle';
  }
}

/** Human label for the lead-source channel (e.g. "Shopify Abandonment Sale"). */
export function leadSourceLabel(leadSource: string | null | undefined): string {
  if (!leadSource) {
    return '—';
  }
  return LEAD_SOURCE_OPTIONS.find((o) => o.value === leadSource)?.label ?? leadSource;
}

/** The courier partner shown for an order row. */
export type CourierPartner = 'IN_HOUSE' | 'QUIKSHIPX' | 'STORE_PICKUP';

/**
 * The courier partner for a list row: 'STORE_PICKUP' for an in-shop (POS) counter
 * sale (no delivery partner), 'IN_HOUSE' when delivered by Shifa's own team, else
 * 'QUIKSHIPX' (the default). A future Blue Dart value would surface here once the
 * backend models it.
 */
export function courierPartner(order: { readonly deliveryMethod?: string | null }): CourierPartner {
  if (order.deliveryMethod === 'STORE_PICKUP') {
    return 'STORE_PICKUP';
  }
  return order.deliveryMethod === 'IN_HOUSE' ? 'IN_HOUSE' : 'QUIKSHIPX';
}

/** Human label for the courier partner column. */
export function courierPartnerLabel(order: { readonly deliveryMethod?: string | null }): string {
  switch (courierPartner(order)) {
    case 'STORE_PICKUP':
      return 'Store pickup';
    case 'IN_HOUSE':
      return 'In-House';
    default:
      return 'QuikShip';
  }
}

/**
 * The courier name to display for an order's shipment card: the actual assigned
 * delivery partner when known, else "Ishika Enterprise" for in-house delivery
 * orders, else "QuikShipX" as the default delivery method (never blank/absent
 * per the client's request).
 */
export function courierDisplayName(order: {
  readonly deliveryMethod?: string | null;
  readonly courierName?: string | null;
}): string {
  if (isOrderStorePickup(order)) {
    return 'Store pickup (POS)';
  }
  if (order.courierName) {
    return order.courierName;
  }
  return isOrderInHouse(order) ? 'Ishika Enterprise' : 'QuikShipX';
}

/**
 * What to show in the "Salesperson / Shopify ID" column: for a Shopify order, the
 * Shopify order id (so staff can cross-reference the Shopify admin); for a
 * salesperson order, the salesperson's name; else a dash.
 */
export function salespersonCell(order: {
  readonly source?: string | null;
  readonly shopifyOrderId?: string | null;
  readonly salespersonName?: string | null;
}): string {
  if (order.source === 'SHOPIFY') {
    return order.shopifyOrderId ? `#${order.shopifyOrderId}` : 'Shopify';
  }
  return order.salespersonName || '—';
}

// --- COD settlement icon -----------------------------------------------------

/** The COD remittance-settlement fields of an Orders-list row (V99). */
export interface CodSettlementSource {
  readonly codSettlementStatus?: 'NONE' | 'SETTLED' | 'PARTIAL' | null;
  readonly codSettledAmount?: string | number | null;
  readonly codSettledShortfall?: string | number | null;
  readonly codSettledDate?: string | null;
}

/** Formats an ISO date as a short IST day for a tooltip (e.g. "13 Oct 2026"). */
function istDay(iso: string): string {
  const d = new Date(iso);
  return Number.isNaN(d.getTime())
    ? iso
    : d.toLocaleDateString('en-IN', { timeZone: 'Asia/Kolkata', day: '2-digit', month: 'short', year: 'numeric' });
}

/**
 * The COD remittance-settlement icon for an Orders-list row (V99), or null when
 * nothing has been settled. SETTLED → a green check with a tooltip of the
 * remitted amount + date; PARTIAL → an amber half-circle with the settled amount,
 * the shortfall still to collect, and the date. Lets staff see at a glance which
 * COD orders the courier has already paid out, and which are short.
 */
export function settlementIcon(
  order: CodSettlementSource,
): { icon: string; tone: string; tooltip: string } | null {
  const status = order.codSettlementStatus;
  if (status !== 'SETTLED' && status !== 'PARTIAL') {
    return null;
  }
  const when = order.codSettledDate ? ` on ${istDay(order.codSettledDate)}` : '';
  const settled = formatInr(order.codSettledAmount, 0);
  if (status === 'PARTIAL') {
    const shortfall = formatInr(order.codSettledShortfall, 0);
    return {
      icon: 'ti-progress-alert',
      tone: 'partial',
      tooltip: `COD partially remitted by the courier${when}: ${settled} received, ${shortfall} shortfall still to collect.`,
    };
  }
  return {
    icon: 'ti-circle-check-filled',
    tone: 'settled',
    tooltip: `COD remitted by the courier${when}: ${settled} settled in full.`,
  };
}

// --- Order-detail drawer helpers ---------------------------------------------

/**
 * Two-letter initials for the customer avatar chip (we have no customer photos,
 * so the detail view uses an initials chip in Shifa green).
 */
export function customerInitials(name: string | null | undefined): string {
  const parts = (name ?? '').trim().split(/\s+/).filter(Boolean);
  if (parts.length === 0) {
    return '?';
  }
  if (parts.length === 1) {
    return parts[0].slice(0, 2).toUpperCase();
  }
  return (parts[0][0] + parts[parts.length - 1][0]).toUpperCase();
}

/** Subtotal = sum of line totals (Money is a decimal string), formatted as ₹ with 2 decimals. */
export function orderSubtotalLabel(order: {
  readonly items?: readonly { readonly lineTotal?: string | number | null }[] | null;
}): string {
  const sum = (order.items ?? []).reduce((acc, li) => acc + Number(li.lineTotal ?? 0), 0);
  return formatInr(sum, 2);
}

/** Whether the order carries a positive discount (drives the discount totals line). */
export function orderHasDiscount(order: { readonly discountAmount?: string | number | null }): boolean {
  return Number(order.discountAmount ?? 0) > 0;
}

/** The discount formatted as a "- ₹X" reduction for the totals breakdown. */
export function orderDiscountLabel(order: { readonly discountAmount?: string | number | null }): string {
  return `- ${formatInr(Number(order.discountAmount ?? 0), 2)}`;
}

/** Google Maps search deep link for the order's delivery address. */
export function orderMapsUrl(order: {
  readonly addressLine?: string | null;
  readonly city?: string | null;
  readonly state?: string | null;
  readonly postalCode?: string | null;
}): string {
  const query = [order.addressLine, order.city, order.state, order.postalCode]
    .filter(Boolean)
    .join(', ');
  return `https://www.google.com/maps/search/?api=1&query=${encodeURIComponent(query)}`;
}

// --- Status timeline ---------------------------------------------------------

/** One rendered step in the order-detail visual status timeline. */
export interface OrderTimelineStep {
  status: OrderStatusGroupKey;
  label: string;
  icon: string;
  /** Timestamp of the matching status-history row, if one exists yet. */
  at: string | null;
  /** This stage has been reached (a history row exists, or it's an earlier stage than current). */
  done: boolean;
  /** This is the order's present stage. */
  current: boolean;
}

/** Icon per timeline stage, matching the group's business meaning. */
const TIMELINE_ICONS: Record<OrderStatusGroupKey, string> = {
  PENDING_APPROVAL: 'ti-hourglass',
  PROCESSING: 'ti-box',
  SHIPPED: 'ti-truck-delivery',
  DELIVERED: 'ti-circle-check',
  FAILED_RETURNED: 'ti-rotate-2',
  CANCELLED: 'ti-x',
  REJECTED: 'ti-ban',
};

/** Friendly label per stage for the timeline (slightly warmer than the filter-tab labels). */
const TIMELINE_LABELS: Record<OrderStatusGroupKey, string> = {
  PENDING_APPROVAL: 'Placed',
  PROCESSING: 'Packed',
  SHIPPED: 'Shipped',
  DELIVERED: 'Delivered',
  FAILED_RETURNED: 'Returned / Failed',
  CANCELLED: 'Cancelled',
  REJECTED: 'Rejected',
};

/** The happy-path stage order (excludes the two terminal-exception groups). */
const HAPPY_PATH: OrderStatusGroupKey[] = ['PENDING_APPROVAL', 'PROCESSING', 'SHIPPED', 'DELIVERED'];

/** The order fields the status timeline reads. */
export interface OrderTimelineSource {
  readonly orderStatus: OrderStatus | string;
  readonly createdAt?: string | null;
  readonly statusHistory?: readonly { readonly toStatus: OrderStatus | string; readonly changedAt: string }[] | null;
}

function buildStep(
  status: OrderStatusGroupKey,
  at: string | null,
  done: boolean,
  current: boolean,
): OrderTimelineStep {
  return {
    status,
    label: TIMELINE_LABELS[status],
    icon: TIMELINE_ICONS[status],
    at,
    done,
    current,
  };
}

/**
 * Builds the order-detail visual status timeline (enhancement: order status
 * timeline) — a friendly "Placed → Packed → Shipped → Delivered" stepper with
 * real timestamps drawn from the order's status-history rows, folding the many
 * raw statuses into the same business-facing groups the Orders page filter uses
 * (so a less-technical salesperson reads one consistent vocabulary).
 *
 * <p>Cancelled/Failed-Returned orders replace the happy path's tail with their
 * own single terminal step, since those are exceptions to (not steps on) the
 * normal delivery journey.
 */
export function orderTimelineSteps(order: OrderTimelineSource): OrderTimelineStep[] {
  const currentGroup = groupForStatus(order.orderStatus);
  const history = order.statusHistory ?? [];

  // Earliest changedAt whose toStatus falls in each group (oldest first data,
  // but be defensive and take the min just in case).
  const firstAtByGroup = new Map<OrderStatusGroupKey, string>();
  for (const row of history) {
    const group = groupForStatus(row.toStatus);
    if (!group) {
      continue;
    }
    const existing = firstAtByGroup.get(group);
    if (!existing || new Date(row.changedAt).getTime() < new Date(existing).getTime()) {
      firstAtByGroup.set(group, row.changedAt);
    }
  }

  // Exception path: cancelled or failed/returned — show Placed then the
  // terminal exception step only (no fabricated "Shipped"/"Delivered" steps
  // that never happened for a cancelled order).
  if (currentGroup === 'CANCELLED' || currentGroup === 'FAILED_RETURNED') {
    const placedAt = firstAtByGroup.get('PENDING_APPROVAL') ?? order.createdAt ?? null;
    const terminalAt = firstAtByGroup.get(currentGroup) ?? null;
    return [
      buildStep('PENDING_APPROVAL', placedAt, true, false),
      buildStep(currentGroup, terminalAt, true, true),
    ];
  }

  const currentIndex = HAPPY_PATH.indexOf(currentGroup || 'PENDING_APPROVAL');
  return HAPPY_PATH.map((stage, i) => {
    const at = stage === 'PENDING_APPROVAL' && !firstAtByGroup.has(stage)
      ? order.createdAt ?? null
      : firstAtByGroup.get(stage) ?? null;
    return buildStep(stage, at, i <= currentIndex, i === currentIndex);
  });
}

// --- Date buckets ------------------------------------------------------------

/**
 * Whether an ISO timestamp falls within the named date bucket (local time):
 * 'today', 'yesterday', 'week' (current calendar week starting Monday), 'month',
 * or anything else (treated as "all"). {@code now} defaults to the current time
 * and is a parameter only so the rule can be exercised with a fixed clock.
 */
export function matchesDateBucket(
  iso: string | undefined,
  bucket: string,
  now: Date = new Date(),
): boolean {
  if (!iso) {
    return false;
  }
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) {
    return false;
  }
  const startOfDay = (x: Date) => new Date(x.getFullYear(), x.getMonth(), x.getDate());
  const today = startOfDay(now);
  const dDay = startOfDay(d);
  switch (bucket) {
    case 'today':
      return dDay.getTime() === today.getTime();
    case 'yesterday': {
      const y = new Date(today);
      y.setDate(y.getDate() - 1);
      return dDay.getTime() === y.getTime();
    }
    case 'week': {
      // Current calendar week starting Monday.
      const dayFromMon = (today.getDay() + 6) % 7;
      const monday = new Date(today);
      monday.setDate(today.getDate() - dayFromMon);
      return d.getTime() >= monday.getTime();
    }
    case 'month':
      return d.getFullYear() === now.getFullYear() && d.getMonth() === now.getMonth();
    default:
      return true;
  }
}
